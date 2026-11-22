package br.com.civicdrive;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

final class CorrecoesSmokeTest {
    private static int checks;
    private static void check(String what,Object got,Object expected){
        checks++;
        if(!String.valueOf(got).equals(String.valueOf(expected)))
            throw new AssertionError(what+": expected "+expected+", got "+got);
    }
    private static String dec(String raw,String head,boolean can){
        return DtcDecoder.decode(raw,head,can).toString();
    }
    public static void main(String[] args){
        check("CAN P0133",dec("43 01 01 33 00 00 00","43",true),"[P0133]");
        check("CAN two",dec("43 02 01 33 01 34 00","43",true),"[P0133, P0134]");
        check("CAN multiframe",dec("00A\r0: 43 04 01 33 01 34\r1: 01 35 01 36 00 00 00","43",true),"[P0133, P0134, P0135, P0136]");
        check("CAN ATS0",dec("43020133013400","43",true),"[P0133, P0134]");
        check("CAN pending",dec("47 01 03 00 00 00 00","47",true),"[P0300]");
        check("CAN permanent",dec("4A 01 04 20 00 00 00","4A",true),"[P0420]");
        check("legacy KWP",dec("43 01 33 01 34 00 00","43",false),"[P0133, P0134]");
        check("no data",dec("NO DATA","43",true),"[]");
        check("CAN protocol auto",DtcDecoder.isCanProtocol("ATDPN\rA6\r"),true);
        check("KWP protocol",DtcDecoder.isCanProtocol("A5"),false);
        String root="https://raw.githubusercontent.com/lincolnvtech/diagram/civic-drive-build/civic-drive/";
        check("trusted",UpdatePolicy.isTrustedApkUrl(root+"civic-drive-v2.0.9.apk",root),true);
        check("no path escape",UpdatePolicy.isTrustedApkUrl(root+"../../../evil/x.apk",root),false);
        check("no percent encoded escape",UpdatePolicy.isTrustedApkUrl(root+"%2e%2e/x.apk",root),false);
        check("no http",UpdatePolicy.isTrustedApkUrl(root.replace("https","http")+"x.apk",root),false);
        check("no false host",UpdatePolicy.isTrustedApkUrl("https://raw.githubusercontent.com.fake.example"+root.substring("https://raw.githubusercontent.com".length())+"x.apk",root),false);
        long now=1000000000L;
        check("clock rewind",UpdatePolicy.isAutoCheckDue(now,now+3600000L,now+3600000L),true);
        check("recent check",UpdatePolicy.isAutoCheckDue(now,now-100000L,now-100000L),false);
        Random rnd=new Random(42);
        String chars="0123456789ABCDEF :\r\nNOTADSRCHI.?><";
        for(int k=0;k<5000;k++){
            StringBuilder b=new StringBuilder();
            for(int j=0,n=rnd.nextInt(70);j<n;j++)b.append(chars.charAt(rnd.nextInt(chars.length())));
            DtcDecoder.decode(b.toString(),"43",true);
            DtcDecoder.decode(b.toString(),"47",false);
        }
        System.out.println("OK: "+checks+" checks + 5000 fuzz inputs");
    }
}
