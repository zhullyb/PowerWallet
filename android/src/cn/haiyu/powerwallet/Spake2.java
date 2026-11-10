package cn.haiyu.powerwallet;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * SPAKE2 over edwards25519, wire-compatible with BoringSSL's spake25519.c as used by
 * adb wireless pairing. Plain BigInteger arithmetic: slow but only runs once per pairing.
 */
public final class Spake2 {
    static final BigInteger P=BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    static final BigInteger L=BigInteger.ONE.shiftLeft(252).add(new BigInteger("27742317777372353535851937790883648493"));
    static final BigInteger D=BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P);
    static final BigInteger I=BigInteger.valueOf(2).modPow(P.subtract(BigInteger.ONE).shiftRight(2),P);
    static final BigInteger[] ZERO={BigInteger.ZERO,BigInteger.ONE};
    static final BigInteger[] BASE;
    static final BigInteger[] M, N;
    static {
        BigInteger y=BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P);
        BigInteger x=xrecover(y); if(x.testBit(0)) x=P.subtract(x);
        BASE=new BigInteger[]{x,y};
        M=genpoint("edwards25519 point generation seed (M)");
        N=genpoint("edwards25519 point generation seed (N)");
    }

    private final boolean alice;
    private final byte[] myName, theirName;
    private BigInteger privateKey, passwordScalar;
    private byte[] passwordHash, myMsg;

    public Spake2(boolean alice, byte[] myName, byte[] theirName) {this.alice=alice;this.myName=myName;this.theirName=theirName;}

    public byte[] generateMessage(byte[] password) throws Exception {
        byte[] r=new byte[64]; new SecureRandom().nextBytes(r);
        privateKey=le(r).mod(L).shiftLeft(3);
        passwordHash=MessageDigest.getInstance("SHA-512").digest(password);
        BigInteger s=le(passwordHash).mod(L);
        // BoringSSL's "password scalar hack": add multiples of l so the scalar is a multiple of 8.
        if(s.testBit(0)) s=s.add(L);
        if(s.testBit(1)) s=s.add(L.shiftLeft(1));
        if(s.testBit(2)) s=s.add(L.shiftLeft(2));
        passwordScalar=s;
        BigInteger[] pstar=add(mul(BASE,privateKey),mul(alice?M:N,passwordScalar));
        return myMsg=encode(pstar);
    }

    public byte[] processMessage(byte[] theirMsg) throws Exception {
        if(theirMsg.length!=32) throw new IllegalArgumentException("Bad SPAKE2 message");
        BigInteger[] qstar=decode(theirMsg);
        if(qstar==null) throw new IllegalArgumentException("Invalid SPAKE2 point");
        BigInteger[] mask=mul(alice?N:M,passwordScalar);
        BigInteger[] q=add(qstar,new BigInteger[]{P.subtract(mask[0]).mod(P),mask[1]});
        byte[] dh=encode(mul(q,privateKey));
        MessageDigest sha=MessageDigest.getInstance("SHA-512");
        if(alice) {prefixed(sha,myName);prefixed(sha,theirName);prefixed(sha,myMsg);prefixed(sha,theirMsg);}
        else {prefixed(sha,theirName);prefixed(sha,myName);prefixed(sha,theirMsg);prefixed(sha,myMsg);}
        prefixed(sha,dh); prefixed(sha,passwordHash);
        return sha.digest();
    }

    private static void prefixed(MessageDigest sha, byte[] data) {
        long n=data.length; byte[] len=new byte[8];
        for(int i=0;i<8;i++) len[i]=(byte)(n>>>(8*i));
        sha.update(len); sha.update(data);
    }

    static BigInteger le(byte[] b) {
        byte[] be=new byte[b.length+1];
        for(int i=0;i<b.length;i++) be[b.length-i]=b[i];
        return new BigInteger(be);
    }
    static BigInteger xrecover(BigInteger y) {
        BigInteger xx=y.multiply(y).subtract(BigInteger.ONE).multiply(D.multiply(y).multiply(y).add(BigInteger.ONE).modInverse(P)).mod(P);
        BigInteger x=xx.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3),P);
        if(!x.multiply(x).subtract(xx).mod(P).equals(BigInteger.ZERO)) x=x.multiply(I).mod(P);
        if(x.testBit(0)) x=P.subtract(x);
        return x;
    }
    static boolean onCurve(BigInteger[] q) {
        BigInteger x2=q[0].multiply(q[0]), y2=q[1].multiply(q[1]);
        return y2.subtract(x2).subtract(BigInteger.ONE).subtract(D.multiply(x2).multiply(y2)).mod(P).signum()==0;
    }
    static BigInteger[] decode(byte[] s) {
        byte[] c=s.clone(); boolean sign=(c[31]&0x80)!=0; c[31]&=0x7f;
        BigInteger y=le(c);
        if(y.compareTo(P)>=0) return null;
        BigInteger x=xrecover(y);
        if(x.testBit(0)!=sign) x=P.subtract(x).mod(P);
        BigInteger[] q={x,y};
        return onCurve(q)?q:null;
    }
    static byte[] encode(BigInteger[] q) {
        byte[] out=new byte[32]; byte[] be=q[1].toByteArray();
        for(int i=0;i<be.length && i<32;i++) out[i]=be[be.length-1-i];
        if(q[0].testBit(0)) out[31]|=(byte)0x80;
        return out;
    }
    static BigInteger[] add(BigInteger[] a, BigInteger[] b) {
        BigInteger t=D.multiply(a[0]).multiply(b[0]).multiply(a[1]).multiply(b[1]).mod(P);
        BigInteger x=a[0].multiply(b[1]).add(a[1].multiply(b[0])).multiply(BigInteger.ONE.add(t).modInverse(P)).mod(P);
        BigInteger y=a[1].multiply(b[1]).add(a[0].multiply(b[0])).multiply(BigInteger.ONE.subtract(t).mod(P).modInverse(P)).mod(P);
        return new BigInteger[]{x,y};
    }
    static BigInteger[] mul(BigInteger[] q, BigInteger k) {
        BigInteger[] r=ZERO;
        for(int i=k.bitLength()-1;i>=0;i--) {r=add(r,r); if(k.testBit(i)) r=add(r,q);}
        return r;
    }
    static BigInteger[] genpoint(String seed) {
        try {
            MessageDigest sha=MessageDigest.getInstance("SHA-256");
            byte[] v=sha.digest(seed.getBytes("US-ASCII"));
            while(true) {
                byte[] c=v.clone(); boolean sign=(c[31]&0x80)!=0; c[31]&=0x7f;
                BigInteger y=le(c), x=xrecover(y);
                if(x.testBit(0)!=sign) x=P.subtract(x);
                BigInteger[] q={x.mod(P),y.mod(P)};
                if(onCurve(q)) return q;
                v=sha.digest(v);
            }
        } catch(Exception e) {throw new IllegalStateException(e);}
    }
    static String hex(byte[] b) {StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format("%02x",x));return s.toString();}
    public static boolean selfTest() throws Exception {
        byte[] a="alice".getBytes(), b="bob".getBytes(), pw="123456".getBytes();
        Spake2 alice=new Spake2(true,a,b), bob=new Spake2(false,b,a);
        byte[] ma=alice.generateMessage(pw), mb=bob.generateMessage(pw);
        boolean same=Arrays.equals(alice.processMessage(mb),bob.processMessage(ma));
        Spake2 eve=new Spake2(false,b,a); byte[] me=eve.generateMessage("654321".getBytes());
        Spake2 alice2=new Spake2(true,a,b); byte[] ma2=alice2.generateMessage(pw);
        return same && !Arrays.equals(alice2.processMessage(me),eve.processMessage(ma2));
    }
}
