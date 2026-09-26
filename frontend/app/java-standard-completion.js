// Common Java 8 standard-library names. This catalog does not resolve receiver types
// or modify imports; package details explain how each name becomes available.
const packages = {
  'java.lang': 'String StringBuilder StringBuffer System Math StrictMath Object Integer Long Double Float Boolean Character Byte Short Number Exception RuntimeException IllegalArgumentException ArithmeticException Comparable Iterable Runnable Thread',
  'java.io': 'BufferedReader BufferedWriter InputStreamReader OutputStreamWriter PrintWriter PrintStream IOException InputStream OutputStream Reader Writer File FileReader FileWriter StringReader StringWriter ByteArrayInputStream ByteArrayOutputStream',
  'java.util': 'Scanner StringTokenizer Arrays Collections ArrayList LinkedList ArrayDeque PriorityQueue HashMap HashSet TreeMap TreeSet LinkedHashMap LinkedHashSet List Map Set Queue Deque Stack Vector Collection Iterator Comparator Optional Objects Random BitSet',
  'java.math': 'BigInteger BigDecimal RoundingMode',
  'java.util.stream': 'Stream IntStream LongStream DoubleStream Collectors',
};
export const javaStandardNames = Object.entries(packages).flatMap(([pkg,names])=>names.split(' ').map(label=>({
  label, type:'class', detail:pkg,
  info:pkg==='java.lang' ? `${pkg}.${label} · import 없이 사용할 수 있어요.`
    : `${pkg}.${label}\n사용하려면 import ${pkg}.${label}; 또는 해당 패키지의 wildcard import가 필요해요. import는 자동으로 추가하지 않습니다.`,
})));
