package cn.haiyu.powerwallet;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLSocket;

/** Just enough of adb's wireless protocol: TLS pairing (SPAKE2) and a one-shot shell command. */
public final class AdbClient {
    private static final int CNXN=0x4e584e43, STLS=0x534c5453, OPEN=0x4e45504f, OKAY=0x59414b4f, WRTE=0x45545257, CLSE=0x45534c43, AUTH=0x48545541;
    private static final int VERSION=0x01000001, STLS_VERSION=0x01000000, MAX_PAYLOAD=1024*1024;

    // ---------------- Pairing ----------------
    public static void pair(AdbKey key, String host, int port, String code, String name) throws Exception {
        try(Socket raw=new Socket()) {
            raw.connect(new InetSocketAddress(host,port),5000); raw.setSoTimeout(15000);
            SSLSocket tls=key.wrap(raw,host,port);
            byte[] exported=android.net.ssl.SSLSockets.exportKeyingMaterial(tls,"adb-label\u0000",null,64);
            byte[] codeBytes=code.trim().getBytes(StandardCharsets.UTF_8);
            byte[] password=Arrays.copyOf(codeBytes,codeBytes.length+exported.length);
            System.arraycopy(exported,0,password,codeBytes.length,exported.length);
            Spake2 spake=new Spake2(true,"adb pair client\u0000".getBytes(StandardCharsets.US_ASCII),"adb pair server\u0000".getBytes(StandardCharsets.US_ASCII));
            DataOutputStream out=new DataOutputStream(tls.getOutputStream());
            DataInputStream in=new DataInputStream(tls.getInputStream());
            writePacket(out,0,spake.generateMessage(password));
            byte[] keyMaterial=spake.processMessage(readPacket(in,0));
            byte[] aesKey=hkdf(keyMaterial,"adb pairing_auth aes-128-gcm key".getBytes(StandardCharsets.US_ASCII),16);
            byte[] info=new byte[8192]; // PeerInfo{type=ADB_RSA_PUB_KEY(0), data[8191]}
            byte[] pub=key.adbPublicKey(name).getBytes(StandardCharsets.UTF_8);
            System.arraycopy(pub,0,info,1,Math.min(pub.length,8190));
            writePacket(out,1,gcm(Cipher.ENCRYPT_MODE,aesKey,0,info));
            byte[] reply;
            try {reply=gcm(Cipher.DECRYPT_MODE,aesKey,0,readPacket(in,1));}
            catch(javax.crypto.AEADBadTagException wrong) {throw new IOException("配对码错误");}
            if(reply.length!=8192) throw new IOException("Unexpected peer info");
        } catch(EOFException closed) {throw new IOException("配对被手机拒绝（配对码错误或已过期）");}
    }
    private static void writePacket(DataOutputStream out, int type, byte[] payload) throws IOException {
        out.writeByte(1); out.writeByte(type); out.writeInt(payload.length); out.write(payload); out.flush();
    }
    private static byte[] readPacket(DataInputStream in, int expectedType) throws IOException {
        int version=in.readUnsignedByte(), type=in.readUnsignedByte(), size=in.readInt();
        if(version!=1 || type!=expectedType || size<=0 || size>16384) throw new IOException("Bad pairing packet");
        byte[] b=new byte[size]; in.readFully(b); return b;
    }
    private static byte[] hkdf(byte[] ikm, byte[] info, int length) throws Exception {
        Mac mac=Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(new byte[32],"HmacSHA256"));
        byte[] prk=mac.doFinal(ikm);
        mac.init(new SecretKeySpec(prk,"HmacSHA256"));
        mac.update(info); mac.update((byte)1);
        return Arrays.copyOf(mac.doFinal(),length);
    }
    private static byte[] gcm(int mode, byte[] key, long sequence, byte[] data) throws Exception {
        byte[] nonce=new byte[12];
        for(int i=0;i<8;i++) nonce[i]=(byte)(sequence>>>(8*i));
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
        c.init(mode,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
        return c.doFinal(data);
    }

    // ---------------- Connection ----------------
    /** Runs one shell command over adb-over-TLS and returns its output. */
    public static String shell(AdbKey key, String host, int port, String command) throws Exception {
        try(Socket raw=new Socket()) {
            raw.connect(new InetSocketAddress(host,port),5000); raw.setSoTimeout(15000);
            writeMessage(raw.getOutputStream(),CNXN,VERSION,MAX_PAYLOAD,"host::\u0000".getBytes(StandardCharsets.US_ASCII));
            Message m=readMessage(raw.getInputStream());
            if(m.command==AUTH) throw new IOException("手机尚未信任本应用的密钥，请先配对");
            if(m.command!=STLS) throw new IOException("Unexpected adb reply "+Integer.toHexString(m.command));
            writeMessage(raw.getOutputStream(),STLS,STLS_VERSION,0,new byte[0]);
            SSLSocket tls;
            try {tls=key.wrap(raw,host,port);}
            catch(javax.net.ssl.SSLException rejected) {throw new IOException("手机拒绝了本应用的密钥，请重新配对",rejected);}
            InputStream in=tls.getInputStream(); OutputStream out=tls.getOutputStream();
            try {m=readMessage(in);}
            catch(javax.net.ssl.SSLException rejected) {throw new IOException("手机拒绝了本应用的密钥，请重新配对",rejected);}
            if(m.command!=CNXN) throw new IOException("adb handshake failed");
            writeMessage(out,OPEN,1,0,("shell:"+command+"\u0000").getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream result=new ByteArrayOutputStream();
            int remote=0;
            while(true) {
                m=readMessage(in);
                if(m.command==OKAY) remote=m.arg0;
                else if(m.command==WRTE) {result.write(m.data); writeMessage(out,OKAY,1,m.arg0,new byte[0]);}
                else if(m.command==CLSE) {if(remote!=0) writeMessage(out,CLSE,1,remote,new byte[0]); break;}
            }
            return result.toString("UTF-8");
        }
    }
    private static final class Message {int command,arg0,arg1;byte[] data;}
    private static void writeMessage(OutputStream out, int command, int arg0, int arg1, byte[] data) throws IOException {
        ByteBuffer b=ByteBuffer.allocate(24+data.length).order(ByteOrder.LITTLE_ENDIAN);
        int sum=0; for(byte x:data) sum+=x&0xff;
        b.putInt(command).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(~command).put(data);
        out.write(b.array()); out.flush();
    }
    private static Message readMessage(InputStream in) throws IOException {
        byte[] h=new byte[24]; new DataInputStream(in).readFully(h);
        ByteBuffer b=ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Message m=new Message(); m.command=b.getInt(); m.arg0=b.getInt(); m.arg1=b.getInt();
        int len=b.getInt(); b.getInt();
        if(b.getInt()!=~m.command || len<0 || len>MAX_PAYLOAD) throw new IOException("Corrupt adb message");
        m.data=new byte[len]; new DataInputStream(in).readFully(m.data);
        return m;
    }

    /** Shell test entry: app_process ... AdbClient pair|shell <dir> <host> <port> [code|command] */
    public static void main(String[] a) throws Exception {
        AdbKey key=AdbKey.load(new File(a[1]));
        if("pair".equals(a[0])) {pair(key,a[2],Integer.parseInt(a[3]),a[4],"powerwallet@test");System.out.println("PAIRED");}
        else System.out.print(shell(key,a[2],Integer.parseInt(a[3]),a[4]));
    }
}
