package br.com.civicdrive;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** OBD-II modes 03/07/0A: handles CAN count byte and ELM multiframe lines. */
final class DtcDecoder {
    static final int MAX_CODES=24;
    private static final Pattern HEX_LINE=
        Pattern.compile("^(?:([0-9A-F]):)?\\s*([0-9A-F ]+)$");

    private DtcDecoder(){}

    static boolean isCanProtocol(String atdpn){
        if(atdpn==null)return false;
        String last="";
        for(String line:atdpn.toUpperCase(Locale.ROOT).split("[\\r\\n]+")){
            String t=line.replaceAll("[^0-9A-Z]","");
            if(!t.isEmpty())last=t;
        }
        char p;
        if(last.length()==2&&last.charAt(0)=='A')p=last.charAt(1);
        else if(last.length()==1)p=last.charAt(0);
        else return false;
        return p=='6'||p=='7'||p=='8'||p=='9'||p=='B'||p=='C';
    }

    static LinkedHashSet<String> decode(String raw,String header,boolean can){
        LinkedHashSet<String> codes=new LinkedHashSet<>();
        if(raw==null||header==null||header.length()!=2)return codes;
        String marker=header.toUpperCase(Locale.ROOT);
        for(String message:assembleMessages(raw)){
            int at=indexOfAligned(message,marker);
            if(at<0)continue;
            String data=message.substring(at+2);
            int slots;
            if(can){
                if(data.length()<2)continue;
                int count=hex(data,0);
                data=data.substring(2);
                slots=Math.min(count,data.length()/4);
            }else slots=data.length()/4;
            for(int i=0;i<slots&&codes.size()<MAX_CODES;i++){
                int hi=hex(data,i*4),lo=hex(data,i*4+2);
                if(hi==0&&lo==0)continue;
                codes.add(format(hi,lo));
            }
        }
        return codes;
    }

    private static List<String> assembleMessages(String raw){
        List<StringBuilder> messages=new ArrayList<>();
        for(String line:raw.toUpperCase(Locale.ROOT).split("[\\r\\n]+")){
            String t=line.trim();
            if(t.isEmpty())continue;
            Matcher m=HEX_LINE.matcher(t);
            if(!m.matches())continue;
            String tag=m.group(1),hex=m.group(2).replace(" ","");
            if(hex.isEmpty())continue;
            if(tag==null){
                if(hex.length()%2!=0)continue;
                messages.add(new StringBuilder(hex));
            }else if(tag.equals("0")||messages.isEmpty()){
                messages.add(new StringBuilder(hex));
            }else messages.get(messages.size()-1).append(hex);
        }
        List<String> out=new ArrayList<>(messages.size());
        for(StringBuilder b:messages)out.add(b.toString());
        return out;
    }

    private static int indexOfAligned(String s,String needle){
        for(int i=s.indexOf(needle);i>=0;i=s.indexOf(needle,i+1))
            if(i%2==0)return i;
        return -1;
    }
    private static int hex(String s,int off){
        return Integer.parseInt(s.substring(off,off+2),16);
    }
    private static String format(int hi,int lo){
        char type="PCBU".charAt((hi>>6)&3);
        return type+String.valueOf((hi>>4)&3)
            +String.format(Locale.ROOT,"%X%02X",hi&15,lo);
    }
}
