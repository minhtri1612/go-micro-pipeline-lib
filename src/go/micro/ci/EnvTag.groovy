package go.micro.ci

class EnvTag {
  static Map patch(String yamlText, String envKey, String fullTag) {
    def out = new StringBuilder()
    def inBlock = false
    def patched = false
    yamlText.readLines().each { line ->
      if (line ==~ /^${java.util.regex.Pattern.quote(envKey)}:\s*/) {
        inBlock = true
        out.append(line).append('\n')
        return
      }
      if (inBlock && line ==~ /^[A-Za-z0-9_-]+:.*/) {
        inBlock = false
      }
      if (inBlock && !patched && line ==~ /^\s+tag:\s*.*/) {
        def indent = (line =~ /^(\s+)/)[0][1]
        out.append("${indent}tag: \"${fullTag}\"\n")
        patched = true
        return
      }
      out.append(line).append('\n')
    }
    return [ok: patched, text: out.toString()]
  }

  static String read(String yamlText, String envKey) {
    def inBlock = false
    def tag = ''
    yamlText.readLines().each { line ->
      if (line ==~ /^${java.util.regex.Pattern.quote(envKey)}:\s*/) {
        inBlock = true
        return
      }
      if (inBlock && line ==~ /^[A-Za-z0-9_-]+:.*/) {
        inBlock = false
      }
      if (inBlock && !tag && line ==~ /^\s+tag:\s*.*/) {
        def m = (line =~ /^\s+tag:\s*"?([^"\s]+)"?/)
        if (m) {
          tag = m[0][1]
        }
      }
    }
    return tag
  }
}
