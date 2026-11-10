package cn.haiyu.powerwallet;

import java.io.*;
import java.math.BigInteger;
import java.net.Socket;
import java.nio.file.Files;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.*;
import javax.net.ssl.*;

/** The app's own adb identity: an RSA-2048 key plus a self-signed certificate for TLS. */
public final class AdbKey {
    private final PrivateKey privateKey;
    private final X509Certificate certificate;

    private AdbKey(PrivateKey key, X509Certificate cert) {privateKey=key;certificate=cert;}

    public static synchronized AdbKey load(File dir) throws Exception {
        File keyFile=new File(dir,"adbkey.pk8"), certFile=new File(dir,"adbkey.crt");
        if(keyFile.exists() && certFile.exists()) {
            PrivateKey key=KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(keyFile.toPath())));
            X509Certificate cert=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(Files.readAllBytes(certFile.toPath())));
            return new AdbKey(key,cert);
        }
        KeyPairGenerator gen=KeyPairGenerator.getInstance("RSA"); gen.initialize(2048);
        KeyPair pair=gen.generateKeyPair();
        byte[] der=selfSigned(pair);
        X509Certificate cert=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
        dir.mkdirs();
        write(keyFile,pair.getPrivate().getEncoded()); write(certFile,der);
        return new AdbKey(pair.getPrivate(),cert);
    }
    private static void write(File f, byte[] data) throws IOException {
        File tmp=new File(f.getPath()+".tmp");
        try(FileOutputStream out=new FileOutputStream(tmp)){out.write(data);}
        tmp.setReadable(false,false); tmp.setReadable(true,true);
        if(!tmp.renameTo(f)) throw new IOException("Cannot save "+f);
    }

    /** adb's on-the-wire public key: base64(android RSAPublicKey struct) + " name". */
    public String adbPublicKey(String name) {
        RSAPublicKey pub=(RSAPublicKey)certificate.getPublicKey();
        BigInteger n=pub.getModulus(), r32=BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv=r32.subtract(n.mod(r32).modInverse(r32));
        BigInteger rr=BigInteger.ONE.shiftLeft(4096).mod(n);
        java.nio.ByteBuffer b=java.nio.ByteBuffer.allocate(524).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.putInt(64); b.putInt(n0inv.intValue()); b.put(le(n,256)); b.put(le(rr,256)); b.putInt(pub.getPublicExponent().intValue());
        return android.util.Base64.encodeToString(b.array(),android.util.Base64.NO_WRAP)+" "+name;
    }
    private static byte[] le(BigInteger v, int size) {
        byte[] be=v.toByteArray(), out=new byte[size];
        for(int i=0;i<size && i<be.length;i++) out[i]=be[be.length-1-i];
        return out;
    }

    public SSLSocket wrap(Socket raw, String host, int port) throws Exception {
        SSLContext ctx=SSLContext.getInstance("TLSv1.3");
        X509ExtendedKeyManager km=new X509ExtendedKeyManager() {
            public String[] getClientAliases(String t, Principal[] i) {return new String[]{"adb"};}
            public String chooseClientAlias(String[] t, Principal[] i, Socket s) {return "adb";}
            public String chooseEngineClientAlias(String[] t, Principal[] i, SSLEngine e) {return "adb";}
            public String[] getServerAliases(String t, Principal[] i) {return null;}
            public String chooseServerAlias(String t, Principal[] i, Socket s) {return null;}
            public X509Certificate[] getCertificateChain(String a) {return new X509Certificate[]{certificate};}
            public PrivateKey getPrivateKey(String a) {return privateKey;}
        };
        // adbd presents a self-signed cert; trust is established by pairing, not by a CA.
        X509TrustManager tm=new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) {}
            public void checkServerTrusted(X509Certificate[] c, String a) {}
            public X509Certificate[] getAcceptedIssuers() {return new X509Certificate[0];}
        };
        ctx.init(new KeyManager[]{km},new TrustManager[]{tm},new SecureRandom());
        SSLSocket s=(SSLSocket)ctx.getSocketFactory().createSocket(raw,host,port,true);
        s.setUseClientMode(true);
        s.setEnabledProtocols(new String[]{"TLSv1.3"});
        s.startHandshake();
        return s;
    }

    // --- Minimal DER for a self-signed X.509 v3 certificate (sha256WithRSAEncryption). ---
    private static byte[] selfSigned(KeyPair pair) throws Exception {
        byte[] sha256Rsa=seq(oid("1.2.840.113549.1.1.11"),new byte[]{0x05,0x00});
        byte[] name=seq(set(seq(oid("2.5.4.3"),tlv(0x0c,"PowerWallet".getBytes("UTF-8")))));
        SimpleDateFormat f=new SimpleDateFormat("yyyyMMddHHmmss'Z'",Locale.US); f.setTimeZone(TimeZone.getTimeZone("UTC"));
        long now=System.currentTimeMillis();
        byte[] validity=seq(tlv(0x18,f.format(new Date(now-86400000L)).getBytes("US-ASCII")),tlv(0x18,f.format(new Date(now+86400000L*3650*3)).getBytes("US-ASCII")));
        byte[] serial=new byte[16]; new SecureRandom().nextBytes(serial); serial[0]&=0x7f; serial[0]|=0x01;
        byte[] tbs=seq(tlv(0xa0,tlv(0x02,new byte[]{2})),tlv(0x02,serial),sha256Rsa,name,validity,name,pair.getPublic().getEncoded());
        Signature sig=Signature.getInstance("SHA256withRSA"); sig.initSign(pair.getPrivate()); sig.update(tbs);
        byte[] s=sig.sign(), bits=new byte[s.length+1]; System.arraycopy(s,0,bits,1,s.length);
        return seq(tbs,sha256Rsa,tlv(0x03,bits));
    }
    private static byte[] tlv(int tag, byte[] v) {
        ByteArrayOutputStream o=new ByteArrayOutputStream(); o.write(tag);
        int n=v.length;
        if(n<128) o.write(n);
        else if(n<256) {o.write(0x81);o.write(n);}
        else if(n<65536) {o.write(0x82);o.write(n>>8);o.write(n&0xff);}
        else {o.write(0x83);o.write(n>>16);o.write((n>>8)&0xff);o.write(n&0xff);}
        o.write(v,0,v.length); return o.toByteArray();
    }
    private static byte[] cat(byte[]... parts) {ByteArrayOutputStream o=new ByteArrayOutputStream();for(byte[] p:parts)o.write(p,0,p.length);return o.toByteArray();}
    private static byte[] seq(byte[]... parts) {return tlv(0x30,cat(parts));}
    private static byte[] set(byte[]... parts) {return tlv(0x31,cat(parts));}
    private static byte[] oid(String dotted) {
        String[] p=dotted.split("\\."); ByteArrayOutputStream o=new ByteArrayOutputStream();
        o.write(Integer.parseInt(p[0])*40+Integer.parseInt(p[1]));
        for(int i=2;i<p.length;i++) {
            long v=Long.parseLong(p[i]); int shift=0; while((v>>(shift+7))!=0) shift+=7;
            for(;shift>0;shift-=7) o.write((int)(0x80|((v>>shift)&0x7f)));
            o.write((int)(v&0x7f));
        }
        return tlv(0x06,o.toByteArray());
    }
}
