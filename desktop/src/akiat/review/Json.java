package akiat.review;

import java.util.*;

/** Small JSON codec for local review files; no network or external runtime dependencies. */
final class Json {
    private final String s; private int p;
    private Json(String s) { this.s=s; }
    static Object parse(String s) { Json j=new Json(s); Object v=j.value(); j.ws(); if(j.p!=s.length()) throw new IllegalArgumentException("Trailing JSON"); return v; }
    private void ws(){while(p<s.length() && (Character.isWhitespace(s.charAt(p))||s.charAt(p)=='\ufeff'))p++;}
    private char take(){if(p>=s.length())throw new IllegalArgumentException("Unexpected end of JSON"); return s.charAt(p++);}
    private void expect(char c){ws();if(take()!=c)throw new IllegalArgumentException("Expected "+c+" at "+p);}
    private Object value(){ws();char c=s.charAt(p);if(c=='"')return string();if(c=='{'){p++;Map<String,Object>m=new LinkedHashMap<>();ws();if(s.charAt(p)=='}'){p++;return m;}do{ws();String k=string();expect(':');m.put(k,value());ws();c=take();if(c=='}')return m;if(c!=',')throw new IllegalArgumentException("JSON object");}while(true);}if(c=='['){p++;List<Object>a=new ArrayList<>();ws();if(s.charAt(p)==']'){p++;return a;}do{a.add(value());ws();c=take();if(c==']')return a;if(c!=',')throw new IllegalArgumentException("JSON array");}while(true);}for(String lit:Arrays.asList("true","false","null")){if(s.startsWith(lit,p)){p+=lit.length();return lit.equals("null")?null:Boolean.valueOf(lit);}}int start=p;while(p<s.length()&&"-+0123456789.eE".indexOf(s.charAt(p))>=0)p++;if(start==p)throw new IllegalArgumentException("JSON value at "+p);return Double.valueOf(s.substring(start,p));}
    private String string(){expect('"');StringBuilder b=new StringBuilder();for(;;){char c=take();if(c=='"')return b.toString();if(c=='\\'){c=take();switch(c){case 'u':b.append((char)Integer.parseInt(s.substring(p,p+4),16));p+=4;break;case 'n':b.append('\n');break;case 'r':b.append('\r');break;case 't':b.append('\t');break;case 'b':b.append('\b');break;case 'f':b.append('\f');break;case '"':case '\\':case '/':b.append(c);break;default:throw new IllegalArgumentException("JSON escape");}}else b.append(c);}}
    static String write(Object v){if(v==null)return "null";if(v instanceof String){StringBuilder b=new StringBuilder("\"");for(char c:((String)v).toCharArray()){if(c=='"'||c=='\\')b.append('\\').append(c);else if(c<32)b.append(String.format("\\u%04x",(int)c));else b.append(c);}return b.append('"').toString();}if(v instanceof Number){if(!Double.isFinite(((Number)v).doubleValue()))throw new IllegalArgumentException("Nonfinite JSON");return v.toString();}if(v instanceof Boolean)return v.toString();if(v instanceof Map){List<String>a=new ArrayList<>();((Map<?,?>)v).forEach((k,x)->a.add(write(k.toString())+":"+write(x)));return "{"+String.join(",",a)+"}";}if(v instanceof Iterable){List<String>a=new ArrayList<>();for(Object x:(Iterable<?>)v)a.add(write(x));return "["+String.join(",",a)+"]";}throw new IllegalArgumentException("Unsupported JSON type");}
    @SuppressWarnings("unchecked") static Map<String,Object> object(Object v){return (Map<String,Object>)v;}
    @SuppressWarnings("unchecked") static List<Object> array(Object v){return (List<Object>)v;}
}
