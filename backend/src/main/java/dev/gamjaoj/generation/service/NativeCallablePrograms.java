package dev.gamjaoj.generation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.gamjaoj.judge.domain.LanguageProfiles;
import dev.gamjaoj.shared.support.JudgeJson;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Trusted adapters for the same callable wire ABI. No client-supplied driver is accepted. */
public final class NativeCallablePrograms {
  private static String support(String file) {
    try (var in = NativeCallablePrograms.class.getResourceAsStream("/callable/" + file)) {
      return new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  // Existing Java manifests may use words reserved in the other language. Stable suffix in its
  // template/driver.
  public static String name(String name, String language) {
    String words =
        language.equals("PYTHON")
            ? "False None True and as assert async await break class continue def del elif else"
                + " except finally for from global if import in is lambda nonlocal not or pass"
                + " raise return try while with yield"
            : "alignas alignof and and_eq asm auto bitand bitor bool catch char char16_t char32_t"
                + " compl constexpr decltype delete double dynamic_cast explicit export extern"
                + " false friend inline mutable namespace noexcept not not_eq nullptr operator or"
                + " or_eq register reinterpret_cast signed sizeof static_assert static_cast"
                + " struct template thread_local true typedef typeid typename union unsigned"
                + " using virtual wchar_t xor xor_eq";
    return Arrays.asList(words.split(" ")).contains(name)
            || name.equals("UserSolution")
            || name.equals("self")
        ? name + "_"
        : name;
  }

  public static String type(String t) {
    if (t.endsWith("[]")) return "std::vector<" + type(t.substring(0, t.length() - 2)) + ">";
    return switch (t) {
      case "long" -> "long long";
      case "String" -> "std::string";
      case "boolean" -> "bool";
      default -> t;
    };
  }

  public static ObjectNode bundle(JsonNode api, String language) {
    return bundle(api, language, 6291456);
  }

  public static void validateInputLimit(int inputLimit) {
    if (inputLimit != 6291456 && (inputLimit < 8388608 || inputLimit > 134217728))
      throw new IllegalArgumentException(
          "callable input limit must match fixed or trusted generated bounds");
  }

  /** The server selects this from its trusted package, never from a submission or custom run. */
  public static ObjectNode bundleForProblem(JsonNode problem, String language, boolean customRun) {
    int inputLimit = 6291456;
    if (!customRun && problem.has("generated")) {
      var generated = problem.path("generated");
      if (!generated.isObject())
        throw new IllegalArgumentException("generated tests object required");
      var declared = generated.path("inputLimit");
      if (declared.isMissingNode()) inputLimit = 8388608;
      else {
        if (!declared.isIntegralNumber() || !declared.canConvertToInt())
          throw new IllegalArgumentException("integer generated input limit required");
        inputLimit = declared.intValue();
        if (inputLimit < 8388608)
          throw new IllegalArgumentException("generated input limit below minimum");
      }
    }
    return bundle(problem.path("api").path("api"), language, inputLimit);
  }

  public static ObjectNode bundle(JsonNode api, String language, int inputLimit) {
    validateInputLimit(inputLimit);
    CallablePrograms.validate(api);
    if (language.equals("JAVA")) return CallablePrograms.bundle(api, inputLimit);
    LanguageProfiles.normalize(language);
    StringBuilder template =
        new StringBuilder(
            language.equals("CPP")
                ? "#include <bits/stdc++.h>\n"
                    + "using namespace std;\n\n"
                    + "class UserSolution {\n"
                    + "public:\n"
                : "class UserSolution:\n");
    StringBuilder driver =
        new StringBuilder(
            support(language.equals("CPP") ? "cpp.cpp" : "python.py")
                .replace("read(6291457)", "read(" + (inputLimit + 1) + ")")
                .replace("len(data) > 6291456", "len(data) > " + inputLimit));
    if (language.equals("CPP"))
      driver
          .append(
              "\n"
                  + "#include \"UserSolution.cpp\"\n"
                  + "int main(){try{std::ios::sync_with_stdio(false);std::cin.tie(nullptr);std::string"
                  + " input;char c;while(std::cin.get(c)){gamja_callable::require(input.size()<")
          .append(inputLimit)
          .append(
              ");input+=c;}auto"
                  + " root=gamja_callable::Parser{input}.parse();gamja_callable::require(root.kind==4&&root.array.size()>=1&&root.array.size()<=100);for(const"
                  + " auto&"
                  + " calls:root.array){gamja_callable::require(calls.kind==4&&calls.array.size()>=1&&calls.array.size()<=1000000);");
    var methods = new LinkedHashMap<String, Object>();
    if (language.equals("CPP")) {
      if (api.path("mode").asText().equals("SINGLE_FUNCTION"))
        driver.append("gamja_callable::require(calls.array.size()==1);");
      driver.append(
          "UserSolution user;size_t position=0;for(const auto&"
              + " call:calls.array){gamja_callable::require(call.kind==4&&!call.array.empty()&&call.array[0].kind==3);const"
              + " auto& method=call.array[0].text;");
      if (api.path("mode").asText().equals("MULTI_API"))
        driver.append("gamja_callable::require(position!=0||method==\"init\");");
      driver.append("position++;");
    }
    for (var m : api.path("methods")) {
      String original = m.path("name").asText(),
          target = name(original, language),
          returns = m.path("returns").asText();
      List<String> params = new ArrayList<>(), args = new ArrayList<>(), types = new ArrayList<>();
      int index = 0;
      for (var p : m.path("parameters")) {
        String t = p.path("type").asText();
        types.add(t);
        params.add(
            language.equals("CPP")
                ? type(t) + " " + name(p.path("name").asText(), language)
                : name(p.path("name").asText(), language));
        args.add(
            t.endsWith("[]")
                ? "gamja_callable::array<"
                    + type(t.substring(0, t.length() - 2))
                    + ">(call.array["
                    + (++index)
                    + "])"
                : "gamja_callable::convert<" + type(t) + ">(call.array[" + (++index) + "])");
      }
      if (language.equals("CPP")) {
        template
            .append("    ")
            .append(type(returns))
            .append(' ')
            .append(target)
            .append('(')
            .append(String.join(", ", params))
            .append(") {\n        // TODO\n");
        if (!returns.equals("void"))
          template
              .append("        return ")
              .append(
                  returns.equals("boolean")
                      ? "false"
                      : returns.equals("String") || returns.endsWith("[]") ? "{}" : "0")
              .append(";\n");
        template.append("    }\n");
        driver
            .append("if(method==\"")
            .append(original)
            .append("\"){gamja_callable::require(call.array.size()==")
            .append(index + 1)
            .append(");");
        String invoke = "user." + target + "(" + String.join(",", args) + ")";
        if (returns.equals("void")) driver.append(invoke).append(';');
        else
          driver
              .append(type(returns))
              .append(" result=")
              .append(invoke)
              .append(";gamja_callable::output(std::cout,result);std::cout<<'\\n';");
        driver.append("}else ");
      } else {
        template
            .append("    def ")
            .append(target)
            .append("(self")
            .append(params.isEmpty() ? "" : ", " + String.join(", ", params))
            .append("):\n        # TODO\n        ")
            .append(
                returns.equals("void")
                    ? "pass"
                    : "return "
                        + (returns.endsWith("[]")
                            ? "[]"
                            : returns.equals("String")
                                ? "\"\""
                                : returns.equals("boolean") ? "False" : "0"))
            .append("\n\n");
        methods.put(original, List.of(target, returns, types));
      }
    }
    if (language.equals("CPP")) {
      template.append("};\n");
      driver.append(
          "{throw std::runtime_error(\"unknown method\");}}}return 0;}catch(const std::exception&"
              + " e){std::cerr<<e.what();return 1;}}\n");
    } else
      driver.insert(
          0,
          "_methods = "
              + JudgeJson.canonical(JudgeJson.JSON.valueToTree(methods))
              + "\n_single = "
              + (api.path("mode").asText().equals("SINGLE_FUNCTION") ? "True" : "False")
              + "\n");
    return CallablePrograms.bundleFields(api, language, driver.toString(), template.toString());
  }
}
