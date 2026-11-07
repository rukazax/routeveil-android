package io.github.rukazax.routeveil;

import org.torproject.onionmasq.BridgeLineParser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Explicit profiles: never let upstream randomly select a transport or silently fall back to direct. */
public final class TransportPlan {
    public static final class Profile {
        public final String id, type, lines;
        Profile(String id, String type, String lines) { this.id=id; this.type=type; this.lines=lines; }
        public boolean bridge() { return lines != null; }
    }
    private TransportPlan() {}

    public static LinkedHashMap<String,String> validateAndGroup(String text) {
        if (text == null) text="";
        if (text.length()>65536) throw new IllegalArgumentException("bridge_size");
        LinkedHashMap<String,String> grouped = new LinkedHashMap<>();
        for (String raw : text.split("\\r?\\n")) {
            String line=raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("Bridge ")) line=line.substring(7).trim();
            if (line.length()>4096 || line.contains("sqs-queue-url=") || line.contains("sqs-creds-str="))
                throw new IllegalArgumentException("unsupported_bridge_option");
            HashMap<String,BridgeLineParser.IPtConfig> parsed = new HashMap<>();
            BridgeLineParser.parseBridgeLine(line,parsed);
            if (parsed.size()!=1) throw new IllegalArgumentException("invalid_bridge");
            String type=parsed.keySet().iterator().next();
            if (!type.equals("obfs4") && !type.equals("snowflake") && !type.equals("webtunnel"))
                throw new IllegalArgumentException("unsupported_bridge");
            if (type.equals("obfs4") && !line.contains(" cert=")) throw new IllegalArgumentException("missing_cert");
            if (type.equals("webtunnel") && !line.contains(" url=https://")) throw new IllegalArgumentException("https_required");
            grouped.merge(type,line,(a,b)->a+"\n"+b);
        }
        return grouped;
    }

    public static List<Profile> create(String mode, String custom, String snowflake, String obfs4) {
        List<Profile> list=new ArrayList<>();
        if ("direct".equals(mode)) { list.add(new Profile("direct","direct",null)); return list; }
        if (!List.of("auto","snowflake","obfs4","webtunnel").contains(mode)) throw new IllegalArgumentException("unknown_transport");
        Map<String,String> supplied=validateAndGroup(custom);
        for(Map.Entry<String,String> e:supplied.entrySet()) {
            if (mode.equals("auto") || mode.equals(e.getKey())) list.add(new Profile("custom-"+e.getKey(),e.getKey(),e.getValue()));
        }
        if (mode.equals("auto") || mode.equals("snowflake")) {
            String lines=validateAndGroup(snowflake).get("snowflake");
            if(lines!=null) {
                int n=0;
                for(String line:lines.split("\n")) list.add(new Profile("builtin-snowflake-"+(++n),"snowflake",line));
            }
        }
        if (mode.equals("auto") || mode.equals("obfs4")) {
            String lines=validateAndGroup(obfs4).get("obfs4");
            if(lines!=null) list.add(new Profile("builtin-obfs4","obfs4",lines));
        }
        if(list.isEmpty()) throw new IllegalArgumentException("bridge_required");
        return new ArrayList<>(list.subList(0,Math.min(4,list.size())));
    }
}
