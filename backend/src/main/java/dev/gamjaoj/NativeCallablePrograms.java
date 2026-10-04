package dev.gamjaoj;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Trusted adapters for the same callable wire ABI. No client-supplied driver is accepted. */
final class NativeCallablePrograms {
    private static String support(String file) {
        try(var in=NativeCallablePrograms.class.getResourceAsStream("/callable/"+file)) {
            return new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8);
        } catch(java.io.IOException e){throw new IllegalStateException(e);}
    }
    // Existing Java manifests may use words reserved in the other language. Stable suffix in its template/driver.
    static String name(String name,String language) {
        String words=language.equals("PYTHON")?"False None True and as assert async await break class continue def del elif else except finally for from global if import in is lambda nonlocal not or pass raise return try while with yield":"alignas alignof and and_eq asm auto bitand bitor bool catch char char16_t char32_t compl constexpr decltype delete double dynamic_cast explicit export extern false friend inline mutable namespace noexcept not not_eq nullptr operator or or_eq register reinterpret_cast signed sizeof static_assert static_cast struct template thread_local true typedef typeid typename union unsigned using virtual wchar_t xor xor_eq";
        return Arrays.asList(words.split(" ")).contains(name)||name.equals("UserSolution")||name.equals("self")?name+"_":name;
    }
    static String type(String t) {
        if(t.endsWith("[]"))return "std::vector<"+type(t.substring(0,t.length()-2))+">";
        return switch(t){case "long"->"long long";case "String"->"std::string";case "boolean"->"bool";default->t;};
    }
    static ObjectNode bundle(JsonNode api,String language) {
        CallablePrograms.validate(api);
        if(language.equals("JAVA"))return CallablePrograms.bundle(api);
        LanguageProfiles.normalize(language);
        StringBuilder template=new StringBuilder(language.equals("CPP")?"#include <bits/stdc++.h>\nusing namespace std;\n\nclass UserSolution {\npublic:\n":"class UserSolution:\n");
        StringBuilder driver=new StringBuilder(support(language.equals("CPP")?"cpp.cpp":"python.py"));
        if(language.equals("CPP"))driver.append("\n#include \"UserSolution.cpp\"\nint main(){try{std::ios::sync_with_stdio(false);std::cin.tie(nullptr);std::string input;char c;while(std::cin.get(c)){gamja_callable::require(input.size()<6291456);input+=c;}auto root=gamja_callable::Parser{input}.parse();gamja_callable::require(root.kind==4&&root.array.size()>=1&&root.array.size()<=100);for(const auto& calls:root.array){gamja_callable::require(calls.kind==4&&calls.array.size()>=1&&calls.array.size()<=1000000);");
        var methods=new LinkedHashMap<String,Object>();
        if(language.equals("CPP")) {
            if(api.path("mode").asText().equals("SINGLE_FUNCTION"))driver.append("gamja_callable::require(calls.array.size()==1);");
            driver.append("UserSolution user;size_t position=0;for(const auto& call:calls.array){gamja_callable::require(call.kind==4&&!call.array.empty()&&call.array[0].kind==3);const auto& method=call.array[0].text;");
            if(api.path("mode").asText().equals("MULTI_API"))driver.append("gamja_callable::require(position!=0||method==\"init\");");
            driver.append("position++;");
        }
        for(var m:api.path("methods")) {
            String original=m.path("name").asText(),target=name(original,language),returns=m.path("returns").asText();
            List<String> params=new ArrayList<>(),args=new ArrayList<>(),types=new ArrayList<>();int index=0;
            for(var p:m.path("parameters")) {
                String t=p.path("type").asText();types.add(t);
                params.add(language.equals("CPP")?type(t)+" "+name(p.path("name").asText(),language):name(p.path("name").asText(),language));
                args.add(t.endsWith("[]")?"gamja_callable::array<"+type(t.substring(0,t.length()-2))+">(call.array["+(++index)+"])":"gamja_callable::convert<"+type(t)+">(call.array["+(++index)+"])");
            }
            if(language.equals("CPP")) {
                template.append("    ").append(type(returns)).append(' ').append(target).append('(').append(String.join(", ",params)).append(") {\n        // TODO\n");
                if(!returns.equals("void"))template.append("        return ").append(returns.equals("boolean")?"false":returns.equals("String")||returns.endsWith("[]")?"{}":"0").append(";\n");
                template.append("    }\n");
                driver.append("if(method==\"").append(original).append("\"){gamja_callable::require(call.array.size()==").append(index+1).append(");");
                String invoke="user."+target+"("+String.join(",",args)+")";
                if(returns.equals("void"))driver.append(invoke).append(';');
                else driver.append(type(returns)).append(" result=").append(invoke).append(";gamja_callable::output(std::cout,result);std::cout<<'\\n';");
                driver.append("}else ");
            } else {
                template.append("    def ").append(target).append("(self").append(params.isEmpty()?"":", "+String.join(", ",params)).append("):\n        # TODO\n        ").append(returns.equals("void")?"pass":"return "+(returns.endsWith("[]")?"[]":returns.equals("String")?"\"\"":returns.equals("boolean")?"False":"0")).append("\n\n");
                methods.put(original,List.of(target,returns,types));
            }
        }
        if(language.equals("CPP")){template.append("};\n");driver.append("{throw std::runtime_error(\"unknown method\");}}}return 0;}catch(const std::exception& e){std::cerr<<e.what();return 1;}}\n");}
        else driver.insert(0,"_methods = "+JudgeJson.canonical(JudgeJson.JSON.valueToTree(methods))+"\n_single = "+(api.path("mode").asText().equals("SINGLE_FUNCTION")?"True":"False")+"\n");
        return CallablePrograms.bundleFields(api,language,driver.toString(),template.toString());
    }
}
