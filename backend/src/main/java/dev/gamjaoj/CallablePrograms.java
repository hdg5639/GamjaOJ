package dev.gamjaoj;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** A deliberately bounded Java callable ABI. Drivers are generated from data, never supplied by a client/model. */
final class CallablePrograms {
    static final Set<String> TYPES=Set.of("int","long","boolean","String","int[]","long[]","boolean[]","String[]");
    static boolean style(String style){return Set.of("COMMAND_MULTI","COMMAND_SINGLE").contains(style);}
    static boolean enabled(JsonNode contract){return contract.has("callable");}
    static String identifier(JsonNode value) {
        String s=value.asText();
        HybridArtifacts.require(s.matches("[A-Za-z][a-zA-Z0-9_]{0,39}")&&!Set.of("class","return","public","void","new","static","int","long","boolean","if","else","for","while","switch","case","default","throw","throws","try","catch","finally","break","continue","do","this","super","null","true","false","package","import","instanceof","synchronized","volatile","transient","native","abstract","final","assert","enum","extends","implements","interface","protected","private","short","byte","char","double","float","const","goto","getClass","wait","notify","notifyAll","clone","finalize","hashCode","equals","toString").contains(s),"INVALID_API_IDENTIFIER");
        return s;
    }
    static JsonNode validate(JsonNode api) {
        HybridArtifacts.fields(api,"mode","methods");
        HybridArtifacts.require(Set.of("MULTI_API","SINGLE_FUNCTION").contains(api.path("mode").asText()),"INVALID_API_MODE");
        var methods=api.path("methods");HybridArtifacts.require(methods.isArray()&&methods.size()>=1&&methods.size()<=16,"INVALID_API_METHODS");
        Set<String> names=new HashSet<>();
        for(var m:methods) {
            HybridArtifacts.fields(m,"name","returns","parameters","description");
            HybridArtifacts.require(names.add(identifier(m.path("name"))),"DUPLICATE_API_METHOD");
            HybridArtifacts.require(TYPES.contains(m.path("returns").asText())||m.path("returns").asText().equals("void"),"INVALID_API_TYPE");
            HybridArtifacts.text(m.path("description"),2000);
            var params=m.path("parameters");HybridArtifacts.require(params.isArray()&&params.size()<=12,"INVALID_API_PARAMETERS");
            Set<String> args=new HashSet<>();
            for(var p:params){HybridArtifacts.fields(p,"name","type");HybridArtifacts.require(args.add(identifier(p.path("name")))&&TYPES.contains(p.path("type").asText()),"INVALID_API_PARAMETERS");}
        }
        if(api.path("mode").asText().equals("SINGLE_FUNCTION"))HybridArtifacts.require(methods.size()==1&&methods.get(0).path("name").asText().equals("solution")&&!methods.get(0).path("returns").asText().equals("void"),"INVALID_SINGLE_FUNCTION");
        else HybridArtifacts.require(methods.get(0).path("name").asText().equals("init")&&methods.get(0).path("returns").asText().equals("void"),"MISSING_API_INIT");
        return api;
    }
    static ObjectNode schema() {
        var s=JudgeJson.parse("""
          {"type":"object","additionalProperties":false,"required":["mode","methods"],"properties":{
          "mode":{"type":"string","enum":["MULTI_API","SINGLE_FUNCTION"]},
          "methods":{"type":"array","minItems":1,"maxItems":16,"items":{"type":"object","additionalProperties":false,"required":["name","returns","parameters","description"],"properties":{
          "name":{"type":"string"},"returns":{"type":"string","enum":["void","int","long","boolean","String","int[]","long[]","boolean[]","String[]"]},"description":{"type":"string"},
          "parameters":{"type":"array","maxItems":12,"items":{"type":"object","additionalProperties":false,"required":["name","type"],"properties":{"name":{"type":"string"},"type":{"type":"string","enum":["int","long","boolean","String","int[]","long[]","boolean[]","String[]"]}}}}}}}}}
          """);
        return (ObjectNode)s;
    }
    static String template(JsonNode api) {
        validate(api);StringBuilder b=new StringBuilder("public class UserSolution {\n");
        for(var m:api.path("methods")) {
            b.append("\n    public ").append(m.path("returns").asText()).append(' ').append(m.path("name").asText()).append('(');
            var params=new ArrayList<String>();m.path("parameters").forEach(p->params.add(p.path("type").asText()+" "+p.path("name").asText()));b.append(String.join(", ",params)).append(") {\n        // TODO\n");
            String t=m.path("returns").asText();if(!t.equals("void"))b.append("        return ").append(t.endsWith("[]")?"new "+t.substring(0,t.length()-2)+"[0]":t.equals("String")?"\"\"":t.equals("boolean")?"false":"0").append(";\n");
            b.append("    }\n");
        }
        return b.append("}\n").toString();
    }
    static ObjectNode bundle(JsonNode api) {
        validate(api);var b=JudgeJson.JSON.createObjectNode().put("format","JAVA_CALLABLE_V1").put("sourceFile","UserSolution.java");
        b.set("api",api.deepCopy());b.put("driver",driver(api)).put("template",template(api));return b;
    }
    static String driver(JsonNode api) {
        validate(api);StringBuilder b=new StringBuilder("public class Main {\n"+SUPPORT+"\npublic static void main(String[] ignored) throws Exception {\n Object root=new Parser(new String(readAll(),java.nio.charset.StandardCharsets.UTF_8)).parse();\n java.util.List<?> cases=list(root); if(cases.size()<1||cases.size()>100)throw new IllegalArgumentException(\"case count\");\n for(Object item:cases){UserSolution user=new UserSolution(); java.util.List<?> calls=list(item);if(calls.isEmpty()||calls.size()>1000000)throw new IllegalArgumentException(\"call count\"); int position=0;\n for(Object call:calls){java.util.List<?> c=list(call);if(c.isEmpty()||!(c.get(0) instanceof String))throw new IllegalArgumentException(\"method\");String method=(String)c.get(0);\n");
        if(api.path("mode").asText().equals("SINGLE_FUNCTION"))b.append("if(calls.size()!=1)throw new IllegalArgumentException(\"single call required\");\n");
        else b.append("if(position==0&&!method.equals(\"init\"))throw new IllegalArgumentException(\"init required\");\n");
        b.append("position++;switch(method){\n");
        for(var m:api.path("methods")) {
            b.append("case \"").append(m.path("name").asText()).append("\": {if(c.size()!=").append(m.path("parameters").size()+1).append(")throw new IllegalArgumentException(\"arity\");\n");
            var args=new ArrayList<String>();int n=1;
            for(var p:m.path("parameters")){String type=p.path("type").asText();args.add("("+type+")convert(c.get("+(n++)+"),\""+type+"\")");}
            String invoke="user."+m.path("name").asText()+"("+String.join(",",args)+")";
            if(m.path("returns").asText().equals("void"))b.append(invoke).append(";\n");else b.append("System.out.println(json(").append(invoke).append("));\n");
            b.append("break;}\n");
        }
        return b.append("default:throw new IllegalArgumentException(\"unknown method\");}}}}\n}\n").toString();
    }
    /** Internal qualification only. Learner submissions remain separate files and cannot select the driver. */
    static String executable(JsonNode api,String source) {
        validate(api);source=HybridArtifacts.unfence(source);
        HybridArtifacts.require(source.matches("(?s).*\\bclass\\s+UserSolution\\b.*")&&!source.matches("(?s).*\\bclass\\s+Main\\b.*"),"INVALID_API_SOURCE");
        return source.replaceFirst("\\bpublic\\s+(final\\s+)?class\\s+UserSolution\\b","class UserSolution")+"\n"+driver(api);
    }
    static final String INSTRUCTIONS="""
      CALLABLE JAVA CONTRACT: The following overrides standard-input Main requirements for callable reference/slow/mutant solutions only. For COMMAND_MULTI or COMMAND_SINGLE the contract must include callable matching the selected mode.
      MULTI_API requires init returning void as first method; SINGLE_FUNCTION has exactly one non-void solution method receiving the entire command list in one or more array parameters and returning the collected results.
      Both use public class UserSolution and public instance methods with the exact typed signatures. Only int,long,boolean,String and their one-dimensional arrays; void only as a return type. No nulls, no custom types, no output parameters.
      Reference, slowSolution and mutants must be UserSolution implementations WITHOUT Main, input reading or output. The server generates the driver from the API manifest. Generator, validator and oracle are standalone public class Main programs.
      Canonical execution input is a JSON array of cases; each case is an array of calls; each call is [methodName,arg1,...]. Example: [[["init",10],["add",3],["query",2]],[["init",5],["query",1]]]. SINGLE_FUNCTION has one ["solution",...] call per case.
      Each case creates a fresh UserSolution; calls within a case share that object. MULTI_API starts each case with init; init may also repeat and must reset all problem state. Multiple cases run in the same process, so static state must also be reset. Up to 100 cases and 1000000 calls per case within the joint input/resource bounds (driver input ceiling 6 MiB UTF-8). Time/memory limit covers the whole input (all cases/calls), not each API call.
      The driver prints one canonical JSON value per non-void return (no output for void). Arrays preserve order; null is invalid. String output escapes every UTF-16 surrogate code unit and character <= U+0020 as \\uXXXX, backslash and quote as JSON escapes. JSON input integers fit signed long; int parameters must fit signed int. Only integral JSON numbers are supported.
      State method semantics, per-method call limits, argument domains and combined maximum call count in the contract and Korean method descriptions. Tiny and large inputs must cover repeated init, multiple cases, mixed updates/queries, arrays, and state reset. The independent oracle computes the same canonical outputs from this wire format. Do not replace callable submission with a standard-input Main solution.
      """;
    private static final String SUPPORT="""
      static byte[] readAll() throws Exception {java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] buf=new byte[8192];int n;while((n=System.in.read(buf))!=-1){if(out.size()+n>6291456)throw new IllegalArgumentException("input too large");out.write(buf,0,n);}return out.toByteArray();}
      static java.util.List<?> list(Object o){if(!(o instanceof java.util.List))throw new IllegalArgumentException("array required");return (java.util.List<?>)o;}
      static Object convert(Object o,String type){
        if(type.endsWith("[]")){java.util.List<?> a=list(o);String t=type.substring(0,type.length()-2);Class<?> c=t.equals("int")?int.class:t.equals("long")?long.class:t.equals("boolean")?boolean.class:String.class;Object r=java.lang.reflect.Array.newInstance(c,a.size());for(int i=0;i<a.size();i++)java.lang.reflect.Array.set(r,i,convert(a.get(i),t));return r;}
        if(type.equals("String")){if(!(o instanceof String))throw new IllegalArgumentException("string");return o;}
        if(type.equals("boolean")){if(!(o instanceof Boolean))throw new IllegalArgumentException("boolean");return o;}
        if(!(o instanceof Long))throw new IllegalArgumentException("integer");long v=((Long)o).longValue();if(type.equals("int")){if(v<Integer.MIN_VALUE||v>Integer.MAX_VALUE)throw new IllegalArgumentException("int range");return Integer.valueOf((int)v);}return Long.valueOf(v);
      }
      static String json(Object o){if(o==null)throw new IllegalArgumentException("null result");if(o instanceof String){String s=(String)o;StringBuilder b=new StringBuilder("\\\"");for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c<=32||Character.isSurrogate(c)){String h=Integer.toHexString(c);b.append("\\\\u");for(int k=h.length();k<4;k++)b.append('0');b.append(h);}else{if(c=='"'||c=='\\\\')b.append('\\\\');b.append(c);}}return b.append('"').toString();}if(o.getClass().isArray()){StringBuilder b=new StringBuilder("[");int n=java.lang.reflect.Array.getLength(o);for(int i=0;i<n;i++){if(i>0)b.append(',');b.append(json(java.lang.reflect.Array.get(o,i)));}return b.append(']').toString();}return o.toString();}
      static class Parser {
        final String s;int p;Parser(String s){this.s=s;}void ws(){while(p<s.length()&&" \\r\\n\\t".indexOf(s.charAt(p))>=0)p++;}
        Object parse(){Object v=value(0);ws();if(p!=s.length())throw new IllegalArgumentException("trailing input");return v;}
        Object value(int depth){if(depth>8)throw new IllegalArgumentException("depth");ws();if(p>=s.length())throw new IllegalArgumentException("unexpected EOF");char c=s.charAt(p++);
          if(c=='['){java.util.List<Object> a=new java.util.ArrayList<Object>();ws();if(p<s.length()&&s.charAt(p)==']'){p++;return a;}while(true){a.add(value(depth+1));ws();if(p>=s.length())throw new IllegalArgumentException("array EOF");c=s.charAt(p++);if(c==']')return a;if(c!=',')throw new IllegalArgumentException("comma");}}
          if(c=='"'){StringBuilder b=new StringBuilder();while(p<s.length()){c=s.charAt(p++);if(c=='"')return b.toString();if(c<32)throw new IllegalArgumentException("control");if(c=='\\\\'){if(p>=s.length())throw new IllegalArgumentException("escape EOF");c=s.charAt(p++);if(c=='u'){if(p+4>s.length())throw new IllegalArgumentException("unicode");c=(char)Integer.parseInt(s.substring(p,p+4),16);p+=4;}else if(c=='n')c='\\n';else if(c=='r')c='\\r';else if(c=='t')c='\\t';else if(c=='b')c='\\b';else if(c=='f')c='\\f';else if(c!='"'&&c!='\\\\'&&c!='/')throw new IllegalArgumentException("escape");}b.append(c);}throw new IllegalArgumentException("string EOF");}
          int start=p-1;if(c=='t'&&s.startsWith("true",start)){p=start+4;return Boolean.TRUE;}if(c=='f'&&s.startsWith("false",start)){p=start+5;return Boolean.FALSE;}
          if(c!='-'&&(c<'0'||c>'9'))throw new IllegalArgumentException("value");while(p<s.length()&&s.charAt(p)>='0'&&s.charAt(p)<='9')p++;String n=s.substring(start,p);if(!n.matches("-?(0|[1-9][0-9]*)"))throw new IllegalArgumentException("number");return Long.valueOf(n);
        }
      }
      """;
}
