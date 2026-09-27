// Member completion after "receiver." for Java 8, C++17 and Python 3, without a language server.
// The receiver's type comes from its declaration (Java/C++) or last assignment (Python) in the document,
// then follows method return types along a chain. Unknown receivers produce no popup rather than guesses.

// ---- Type strings: "Map<Integer, List<Integer>>" -> {base:'Map', args:[...]} ----------------------
export function parseType(text) {
  text = (text || '').replace(/\s+/g, ' ').trim().replace(/^(final|const|static)\s+/, '').replace(/^std::/, '');
  let array = 0;
  while (/\[\s*\]$/.test(text)) { array++; text = text.replace(/\[\s*\]$/, '').trim(); }
  const open = text.indexOf('<');
  let base = open < 0 ? text : text.slice(0, open).trim(), args = [];
  if (open >= 0) {
    const inner = text.slice(open + 1, text.lastIndexOf('>'));
    let depth = 0, start = 0;
    for (let i = 0; i <= inner.length; i++) {
      const c = inner[i];
      if (c === '<') depth++; else if (c === '>') depth--;
      else if ((c === ',' && depth === 0) || i === inner.length) { const part = inner.slice(start, i).trim(); if (part) args.push(parseType(part)); start = i + 1; }
    }
  }
  base = base.replace(/^std::/, '').replace(/^java\.util\./, '').replace(/[&*]+$/, '').trim();
  if (base === 'Map.Entry') base = 'Entry';
  let type = { base, args };
  for (let i = 0; i < array; i++) type = { base: '[]', args: [type] };
  return type;
}
const T = text => parseType(text);

// ---- Catalogs --------------------------------------------------------------------------------------
// Each member: [label, kind, signature, returnType?]. Return types may use the family's type parameters.
function members(kind, rows) {
  return rows.trim().split('\n').map(line => {
    const [label, signature, returns] = line.trim().split('|').map(s => s && s.trim());
    return { label, kind: label.endsWith('()') || signature?.includes('(') ? 'method' : kind, signature, returns };
  }).map(m => ({ ...m, label: m.label.replace(/\(\)$/, '') }));
}
const objectMembers = members('method', `
  equals|equals(Object) boolean|boolean
  hashCode|hashCode() int|int
  toString|toString() String|String`);
const javaCollection = members('method', `
  size|size() int|int
  isEmpty|isEmpty() boolean|boolean
  contains|contains(Object) boolean|boolean
  clear|clear() void
  iterator|iterator() Iterator<E>|Iterator<E>
  stream|stream() Stream<E>|Stream<E>
  toArray|toArray() Object[]
  forEach|forEach(Consumer<E>) void
  addAll|addAll(Collection<E>) boolean|boolean
  removeIf|removeIf(Predicate<E>) boolean|boolean`);
const javaQueue = members('method', `
  offer|offer(E) boolean|boolean
  add|add(E) boolean|boolean
  poll|poll() E|E
  peek|peek() E|E
  remove|remove() E|E
  element|element() E|E`);
const javaDeque = members('method', `
  offerFirst|offerFirst(E) boolean|boolean
  offerLast|offerLast(E) boolean|boolean
  pollFirst|pollFirst() E|E
  pollLast|pollLast() E|E
  peekFirst|peekFirst() E|E
  peekLast|peekLast() E|E
  addFirst|addFirst(E) void
  addLast|addLast(E) void
  removeFirst|removeFirst() E|E
  removeLast|removeLast() E|E
  getFirst|getFirst() E|E
  getLast|getLast() E|E
  push|push(E) void
  pop|pop() E|E
  descendingIterator|descendingIterator() Iterator<E>|Iterator<E>`);
const javaList = members('method', `
  add|add(E) / add(int, E) boolean|boolean
  get|get(int) E|E
  set|set(int, E) E|E
  remove|remove(int) E / remove(Object) boolean|E
  indexOf|indexOf(Object) int|int
  lastIndexOf|lastIndexOf(Object) int|int
  sort|sort(Comparator<E>) void
  subList|subList(int, int) List<E>|List<E>
  listIterator|listIterator() ListIterator<E>|Iterator<E>`);
const javaSet = members('method', `
  add|add(E) boolean|boolean
  remove|remove(Object) boolean|boolean
  containsAll|containsAll(Collection) boolean|boolean
  retainAll|retainAll(Collection) boolean|boolean
  removeAll|removeAll(Collection) boolean|boolean`);
const javaTreeSet = members('method', `
  first|first() E|E
  last|last() E|E
  floor|floor(E) E|E
  ceiling|ceiling(E) E|E
  higher|higher(E) E|E
  lower|lower(E) E|E
  pollFirst|pollFirst() E|E
  pollLast|pollLast() E|E
  headSet|headSet(E) SortedSet<E>|TreeSet<E>
  tailSet|tailSet(E) SortedSet<E>|TreeSet<E>
  subSet|subSet(E, E) SortedSet<E>|TreeSet<E>
  descendingSet|descendingSet() NavigableSet<E>|TreeSet<E>`);
const javaMap = members('method', `
  put|put(K, V) V|V
  get|get(Object) V|V
  getOrDefault|getOrDefault(Object, V) V|V
  containsKey|containsKey(Object) boolean|boolean
  containsValue|containsValue(Object) boolean|boolean
  remove|remove(Object) V|V
  size|size() int|int
  isEmpty|isEmpty() boolean|boolean
  clear|clear() void
  keySet|keySet() Set<K>|Set<K>
  values|values() Collection<V>|Collection<V>
  entrySet|entrySet() Set<Map.Entry<K,V>>|Set<Map.Entry<K,V>>
  putIfAbsent|putIfAbsent(K, V) V|V
  merge|merge(K, V, BiFunction) V|V
  computeIfAbsent|computeIfAbsent(K, Function) V|V
  computeIfPresent|computeIfPresent(K, BiFunction) V|V
  compute|compute(K, BiFunction) V|V
  forEach|forEach(BiConsumer<K,V>) void
  putAll|putAll(Map) void`);
const javaTreeMap = members('method', `
  firstKey|firstKey() K|K
  lastKey|lastKey() K|K
  floorKey|floorKey(K) K|K
  ceilingKey|ceilingKey(K) K|K
  higherKey|higherKey(K) K|K
  lowerKey|lowerKey(K) K|K
  firstEntry|firstEntry() Map.Entry<K,V>|Map.Entry<K,V>
  lastEntry|lastEntry() Map.Entry<K,V>|Map.Entry<K,V>
  floorEntry|floorEntry(K) Map.Entry<K,V>|Map.Entry<K,V>
  ceilingEntry|ceilingEntry(K) Map.Entry<K,V>|Map.Entry<K,V>
  pollFirstEntry|pollFirstEntry() Map.Entry<K,V>|Map.Entry<K,V>
  pollLastEntry|pollLastEntry() Map.Entry<K,V>|Map.Entry<K,V>
  headMap|headMap(K) SortedMap<K,V>|TreeMap<K,V>
  tailMap|tailMap(K) SortedMap<K,V>|TreeMap<K,V>
  descendingMap|descendingMap() NavigableMap<K,V>|TreeMap<K,V>`);
const javaString = members('method', `
  length|length() int|int
  charAt|charAt(int) char|char
  substring|substring(int, int) String|String
  indexOf|indexOf(String) int|int
  lastIndexOf|lastIndexOf(String) int|int
  equals|equals(Object) boolean|boolean
  equalsIgnoreCase|equalsIgnoreCase(String) boolean|boolean
  compareTo|compareTo(String) int|int
  contains|contains(CharSequence) boolean|boolean
  startsWith|startsWith(String) boolean|boolean
  endsWith|endsWith(String) boolean|boolean
  isEmpty|isEmpty() boolean|boolean
  split|split(String) String[]|String[]
  trim|trim() String|String
  toCharArray|toCharArray() char[]|char[]
  toUpperCase|toUpperCase() String|String
  toLowerCase|toLowerCase() String|String
  replace|replace(CharSequence, CharSequence) String|String
  replaceAll|replaceAll(String, String) String|String
  concat|concat(String) String|String
  matches|matches(String) boolean|boolean
  chars|chars() IntStream|IntStream
  hashCode|hashCode() int|int`);
const javaBuilder = members('method', `
  append|append(x) StringBuilder|SELF
  insert|insert(int, x) StringBuilder|SELF
  reverse|reverse() StringBuilder|SELF
  toString|toString() String|String
  length|length() int|int
  charAt|charAt(int) char|char
  setCharAt|setCharAt(int, char) void
  deleteCharAt|deleteCharAt(int) StringBuilder|SELF
  delete|delete(int, int) StringBuilder|SELF
  setLength|setLength(int) void
  indexOf|indexOf(String) int|int
  replace|replace(int, int, String) StringBuilder|SELF
  substring|substring(int, int) String|String`);
const javaPrint = members('method', `
  println|println(x) void
  print|print(x) void
  printf|printf(String, Object...) PrintStream|SELF
  write|write(String) void
  flush|flush() void
  close|close() void`);
const javaStream = members('method', `
  map|map(Function) Stream|Stream<E>
  filter|filter(Predicate<E>) Stream<E>|Stream<E>
  sorted|sorted() Stream<E>|Stream<E>
  distinct|distinct() Stream<E>|Stream<E>
  forEach|forEach(Consumer<E>) void
  collect|collect(Collector) R
  count|count() long|long
  mapToInt|mapToInt(ToIntFunction<E>) IntStream|IntStream
  toArray|toArray() Object[]
  limit|limit(long) Stream<E>|Stream<E>
  anyMatch|anyMatch(Predicate<E>) boolean|boolean
  allMatch|allMatch(Predicate<E>) boolean|boolean`);
const javaIntStream = members('method', `
  sum|sum() int|int
  max|max() OptionalInt
  min|min() OptionalInt
  average|average() OptionalDouble
  toArray|toArray() int[]|int[]
  boxed|boxed() Stream<Integer>|Stream<Integer>
  map|map(IntUnaryOperator) IntStream|IntStream
  filter|filter(IntPredicate) IntStream|IntStream
  sorted|sorted() IntStream|IntStream
  count|count() long|long
  forEach|forEach(IntConsumer) void`);
const boxed = members('method', `
  intValue|intValue() int|int
  longValue|longValue() long|long
  doubleValue|doubleValue() double|double
  compareTo|compareTo(x) int|int
  equals|equals(Object) boolean|boolean
  toString|toString() String|String`);
const java = {
  String: javaString,
  StringBuilder: javaBuilder, StringBuffer: javaBuilder,
  BufferedReader: members('method', `
    readLine|readLine() String|String
    read|read() int|int
    ready|ready() boolean|boolean
    lines|lines() Stream<String>|Stream<String>
    close|close() void`),
  BufferedWriter: members('method', `
    write|write(String) void
    newLine|newLine() void
    append|append(CharSequence) Writer|SELF
    flush|flush() void
    close|close() void`),
  StringTokenizer: members('method', `
    nextToken|nextToken() String|String
    hasMoreTokens|hasMoreTokens() boolean|boolean
    countTokens|countTokens() int|int`),
  Scanner: members('method', `
    nextInt|nextInt() int|int
    nextLong|nextLong() long|long
    nextDouble|nextDouble() double|double
    next|next() String|String
    nextLine|nextLine() String|String
    hasNext|hasNext() boolean|boolean
    hasNextInt|hasNextInt() boolean|boolean
    hasNextLine|hasNextLine() boolean|boolean
    close|close() void`),
  PrintWriter: javaPrint, PrintStream: javaPrint,
  List: [...javaList, ...javaCollection], ArrayList: [...javaList, ...javaCollection], Vector: [...javaList, ...javaCollection],
  LinkedList: [...javaList, ...javaDeque, ...javaQueue, ...javaCollection],
  Collection: javaCollection, Iterable: members('method', `iterator|iterator() Iterator<E>|Iterator<E>\n forEach|forEach(Consumer<E>) void`),
  Set: [...javaSet, ...javaCollection], HashSet: [...javaSet, ...javaCollection], LinkedHashSet: [...javaSet, ...javaCollection],
  TreeSet: [...javaSet, ...javaTreeSet, ...javaCollection], SortedSet: [...javaSet, ...javaTreeSet, ...javaCollection], NavigableSet: [...javaSet, ...javaTreeSet, ...javaCollection],
  Map: javaMap, HashMap: javaMap, LinkedHashMap: javaMap,
  TreeMap: [...javaMap, ...javaTreeMap], SortedMap: [...javaMap, ...javaTreeMap], NavigableMap: [...javaMap, ...javaTreeMap],
  Entry: members('method', `
    getKey|getKey() K|K
    getValue|getValue() V|V
    setValue|setValue(V) V|V`),
  Queue: [...javaQueue, ...javaCollection], PriorityQueue: [...javaQueue, ...javaCollection],
  Deque: [...javaDeque, ...javaQueue, ...javaCollection], ArrayDeque: [...javaDeque, ...javaQueue, ...javaCollection],
  Stack: [...members('method', `
    push|push(E) E|E
    pop|pop() E|E
    peek|peek() E|E
    empty|empty() boolean|boolean
    search|search(Object) int|int
    get|get(int) E|E`), ...javaCollection],
  Iterator: members('method', `
    hasNext|hasNext() boolean|boolean
    next|next() E|E
    remove|remove() void`),
  Stream: javaStream, IntStream: javaIntStream, LongStream: javaIntStream,
  Integer: boxed, Long: boxed, Double: boxed, Character: members('method', `charValue|charValue() char|char\n compareTo|compareTo(Character) int|int\n equals|equals(Object) boolean|boolean`),
  '[]': members('property', `
    length|length int|int
    clone|clone() array|SELF`),
};
const typeParams = { List:['E'], ArrayList:['E'], LinkedList:['E'], Vector:['E'], Collection:['E'], Iterable:['E'], Set:['E'], HashSet:['E'], LinkedHashSet:['E'],
  TreeSet:['E'], SortedSet:['E'], NavigableSet:['E'], Queue:['E'], PriorityQueue:['E'], Deque:['E'], ArrayDeque:['E'], Stack:['E'], Iterator:['E'], Stream:['E'],
  Map:['K','V'], HashMap:['K','V'], LinkedHashMap:['K','V'], TreeMap:['K','V'], SortedMap:['K','V'], NavigableMap:['K','V'], Entry:['K','V'] };
const javaStatics = {
  Math: members('method', `
    max|max(a, b)|NUM
    min|min(a, b)|NUM
    abs|abs(x)|NUM
    pow|pow(double, double) double|double
    sqrt|sqrt(double) double|double
    cbrt|cbrt(double) double|double
    floor|floor(double) double|double
    ceil|ceil(double) double|double
    round|round(double) long|long
    log|log(double) double|double
    log10|log10(double) double|double
    hypot|hypot(double, double) double|double
    floorDiv|floorDiv(a, b)|NUM
    floorMod|floorMod(a, b)|NUM
    addExact|addExact(a, b)|NUM
    multiplyExact|multiplyExact(a, b)|NUM
    random|random() double|double
    PI|PI double|double
    E|E double|double`),
  Integer: members('method', `
    parseInt|parseInt(String) int|int
    valueOf|valueOf(x) Integer|Integer
    toString|toString(int) String|String
    toBinaryString|toBinaryString(int) String|String
    bitCount|bitCount(int) int|int
    compare|compare(int, int) int|int
    max|max(int, int) int|int
    min|min(int, int) int|int
    sum|sum(int, int) int|int
    MAX_VALUE|MAX_VALUE int|int
    MIN_VALUE|MIN_VALUE int|int`),
  Long: members('method', `
    parseLong|parseLong(String) long|long
    valueOf|valueOf(x) Long|Long
    toString|toString(long) String|String
    compare|compare(long, long) int|int
    bitCount|bitCount(long) int|int
    MAX_VALUE|MAX_VALUE long|long
    MIN_VALUE|MIN_VALUE long|long`),
  Double: members('method', `
    parseDouble|parseDouble(String) double|double
    compare|compare(double, double) int|int
    MAX_VALUE|MAX_VALUE double|double`),
  Character: members('method', `
    isDigit|isDigit(char) boolean|boolean
    isLetter|isLetter(char) boolean|boolean
    isLetterOrDigit|isLetterOrDigit(char) boolean|boolean
    isUpperCase|isUpperCase(char) boolean|boolean
    isLowerCase|isLowerCase(char) boolean|boolean
    isWhitespace|isWhitespace(char) boolean|boolean
    toUpperCase|toUpperCase(char) char|char
    toLowerCase|toLowerCase(char) char|char
    getNumericValue|getNumericValue(char) int|int`),
  String: members('method', `
    valueOf|valueOf(x) String|String
    join|join(CharSequence, elements) String|String
    format|format(String, Object...) String|String`),
  Arrays: members('method', `
    sort|sort(array) void
    fill|fill(array, value) void
    asList|asList(T...) List<T>
    toString|toString(array) String|String
    deepToString|deepToString(array) String|String
    copyOf|copyOf(array, int) array
    copyOfRange|copyOfRange(array, int, int) array
    stream|stream(array) Stream
    equals|equals(array, array) boolean|boolean
    binarySearch|binarySearch(array, key) int|int`),
  Collections: members('method', `
    sort|sort(List) void
    reverse|reverse(List) void
    max|max(Collection)
    min|min(Collection)
    swap|swap(List, int, int) void
    shuffle|shuffle(List) void
    frequency|frequency(Collection, Object) int|int
    nCopies|nCopies(int, T) List<T>
    reverseOrder|reverseOrder() Comparator
    emptyList|emptyList() List
    unmodifiableList|unmodifiableList(List) List`),
  System: members('method', `
    out|out PrintStream|PrintStream
    err|err PrintStream|PrintStream
    in|in InputStream
    currentTimeMillis|currentTimeMillis() long|long
    nanoTime|nanoTime() long|long
    exit|exit(int) void
    arraycopy|arraycopy(src, int, dest, int, int) void
    lineSeparator|lineSeparator() String|String`),
  Objects: members('method', `
    equals|equals(Object, Object) boolean|boolean
    hash|hash(Object...) int|int
    requireNonNull|requireNonNull(T) T
    isNull|isNull(Object) boolean|boolean`),
  Boolean: members('method', `parseBoolean|parseBoolean(String) boolean|boolean`),
};
for (const statics of Object.values(javaStatics)) for (const m of statics) if (!m.signature.includes('(')) m.kind = 'property';

const cppSequence = members('method', `
  size|size() size_t|size_t
  empty|empty() bool|bool
  clear|clear() void
  begin|begin() iterator
  end|end() iterator`);
const cpp = {
  vector: [...members('method', `
    push_back|push_back(T) void
    emplace_back|emplace_back(args...) void
    pop_back|pop_back() void
    front|front() T&|T
    back|back() T&|T
    at|at(size_t) T&|T
    resize|resize(size_t) void
    reserve|reserve(size_t) void
    insert|insert(iterator, T) iterator
    erase|erase(iterator) iterator
    assign|assign(size_t, T) void
    rbegin|rbegin() reverse_iterator
    rend|rend() reverse_iterator
    data|data() T*
    capacity|capacity() size_t|size_t`), ...cppSequence],
  string: [...members('method', `
    length|length() size_t|size_t
    substr|substr(pos, len) string|string
    find|find(string) size_t|size_t
    rfind|rfind(string) size_t|size_t
    push_back|push_back(char) void
    pop_back|pop_back() void
    append|append(string) string&|string
    insert|insert(pos, string) string&|string
    erase|erase(pos, len) string&|string
    replace|replace(pos, len, string) string&|string
    compare|compare(string) int|int
    front|front() char&|char
    back|back() char&|char
    at|at(size_t) char&|char
    c_str|c_str() const char*`), ...cppSequence],
  deque: [...members('method', `
    push_back|push_back(T) void
    push_front|push_front(T) void
    pop_back|pop_back() void
    pop_front|pop_front() void
    emplace_back|emplace_back(args...) void
    emplace_front|emplace_front(args...) void
    front|front() T&|T
    back|back() T&|T
    at|at(size_t) T&|T`), ...cppSequence],
  list: [...members('method', `
    push_back|push_back(T) void
    push_front|push_front(T) void
    pop_back|pop_back() void
    pop_front|pop_front() void
    front|front() T&|T
    back|back() T&|T
    sort|sort() void
    reverse|reverse() void`), ...cppSequence],
  queue: members('method', `
    push|push(T) void
    emplace|emplace(args...) void
    pop|pop() void
    front|front() T&|T
    back|back() T&|T
    size|size() size_t|size_t
    empty|empty() bool|bool`),
  priority_queue: members('method', `
    push|push(T) void
    emplace|emplace(args...) void
    pop|pop() void
    top|top() const T&|T
    size|size() size_t|size_t
    empty|empty() bool|bool`),
  stack: members('method', `
    push|push(T) void
    emplace|emplace(args...) void
    pop|pop() void
    top|top() T&|T
    size|size() size_t|size_t
    empty|empty() bool|bool`),
  set: [...members('method', `
    insert|insert(T) pair<iterator,bool>
    emplace|emplace(args...) pair<iterator,bool>
    erase|erase(T) size_t|size_t
    find|find(T) iterator
    count|count(T) size_t|size_t
    lower_bound|lower_bound(T) iterator
    upper_bound|upper_bound(T) iterator
    rbegin|rbegin() reverse_iterator
    rend|rend() reverse_iterator`), ...cppSequence],
  map: [...members('method', `
    insert|insert(pair<K,V>) pair<iterator,bool>
    emplace|emplace(K, V) pair<iterator,bool>
    erase|erase(K) size_t|size_t
    find|find(K) iterator
    count|count(K) size_t|size_t
    at|at(K) V&|V
    lower_bound|lower_bound(K) iterator
    upper_bound|upper_bound(K) iterator
    rbegin|rbegin() reverse_iterator
    rend|rend() reverse_iterator`), ...cppSequence],
  pair: members('property', `
    first|first A|A
    second|second B|B`),
  bitset: members('method', `
    set|set(pos) bitset&
    reset|reset(pos) bitset&
    flip|flip(pos) bitset&
    test|test(pos) bool|bool
    count|count() size_t|size_t
    size|size() size_t|size_t
    any|any() bool|bool
    none|none() bool|bool
    all|all() bool|bool
    to_string|to_string() string|string`),
  istream: members('method', `
    tie|tie(nullptr) ostream*
    ignore|ignore() istream&
    get|get() int|int
    peek|peek() int|int
    eof|eof() bool|bool
    fail|fail() bool|bool
    getline|getline(char*, n) istream&`),
  ostream: members('method', `
    precision|precision(n) streamsize
    flush|flush() ostream&
    tie|tie(nullptr) ostream*
    setf|setf(flags) fmtflags`),
};
cpp.multiset = cpp.set; cpp.unordered_set = cpp.set; cpp.unordered_multiset = cpp.set;
cpp.multimap = cpp.map; cpp.unordered_map = cpp.map;
const cppParams = { vector:['T'], deque:['T'], list:['T'], queue:['T'], priority_queue:['T'], stack:['T'], set:['T'], multiset:['T'], unordered_set:['T'],
  unordered_multiset:['T'], map:['K','V'], multimap:['K','V'], unordered_map:['K','V'], pair:['A','B'] };
const cppGlobals = { cin:'istream', cout:'ostream', cerr:'ostream' };

const pyStr = members('method', `
  split|split(sep=None) list[str]|list<str>
  strip|strip() str|str
  rstrip|rstrip() str|str
  lstrip|lstrip() str|str
  join|join(iterable) str|str
  replace|replace(old, new) str|str
  startswith|startswith(prefix) bool
  endswith|endswith(suffix) bool
  find|find(sub) int|int
  rfind|rfind(sub) int|int
  index|index(sub) int|int
  count|count(sub) int|int
  upper|upper() str|str
  lower|lower() str|str
  isdigit|isdigit() bool
  isalpha|isalpha() bool
  isalnum|isalnum() bool
  isspace|isspace() bool
  isupper|isupper() bool
  islower|islower() bool
  format|format(*args) str|str
  zfill|zfill(width) str|str
  splitlines|splitlines() list[str]|list<str>
  encode|encode() bytes`);
const python = {
  str: pyStr,
  list: members('method', `
    append|append(x) None
    extend|extend(iterable) None
    insert|insert(i, x) None
    pop|pop(i=-1) item|T
    remove|remove(x) None
    clear|clear() None
    index|index(x) int|int
    count|count(x) int|int
    sort|sort(key=None, reverse=False) None
    reverse|reverse() None
    copy|copy() list|SELF`),
  dict: members('method', `
    get|get(key, default=None) value|V
    keys|keys() dict_keys
    values|values() dict_values
    items|items() dict_items
    pop|pop(key) value|V
    setdefault|setdefault(key, default) value|V
    update|update(other) None
    clear|clear() None
    copy|copy() dict|SELF`),
  set: members('method', `
    add|add(x) None
    remove|remove(x) None
    discard|discard(x) None
    pop|pop() item
    clear|clear() None
    union|union(other) set|SELF
    intersection|intersection(other) set|SELF
    difference|difference(other) set|SELF
    symmetric_difference|symmetric_difference(other) set|SELF
    issubset|issubset(other) bool
    issuperset|issuperset(other) bool
    update|update(other) None
    copy|copy() set|SELF`),
  deque: members('method', `
    append|append(x) None
    appendleft|appendleft(x) None
    pop|pop() item|T
    popleft|popleft() item|T
    extend|extend(iterable) None
    extendleft|extendleft(iterable) None
    rotate|rotate(n) None
    clear|clear() None
    count|count(x) int|int
    index|index(x) int|int
    maxlen|maxlen int|int`),
  int: members('method', `
    bit_length|bit_length() int|int
    bit_count|bit_count() int|int
    to_bytes|to_bytes(length, byteorder) bytes`),
  tuple: members('method', `
    count|count(x) int|int
    index|index(x) int|int`),
  TextIO: members('method', `
    readline|readline() str|str
    read|read() str|str
    readlines|readlines() list[str]|list<str>
    write|write(s) int|int
    flush|flush() None`),
};
python.Counter = [...python.dict, ...members('method', `
  most_common|most_common(n) list[tuple]|list<tuple>
  elements|elements() iterator
  subtract|subtract(other) None
  total|total() int|int`)];
python.defaultdict = python.dict;
const pyModules = {
  sys: members('function', `
    stdin|stdin TextIO|TextIO
    stdout|stdout TextIO|TextIO
    setrecursionlimit|setrecursionlimit(n) None
    exit|exit(code) None
    maxsize|maxsize int|int`),
  math: members('function', `
    sqrt|sqrt(x) float
    isqrt|isqrt(n) int|int
    gcd|gcd(a, b) int|int
    lcm|lcm(a, b) int|int
    ceil|ceil(x) int|int
    floor|floor(x) int|int
    log|log(x, base) float
    log2|log2(x) float
    log10|log10(x) float
    comb|comb(n, k) int|int
    perm|perm(n, k) int|int
    factorial|factorial(n) int|int
    hypot|hypot(x, y) float
    inf|inf float
    pi|pi float`),
  heapq: members('function', `
    heappush|heappush(heap, item) None
    heappop|heappop(heap) item
    heapify|heapify(list) None
    heappushpop|heappushpop(heap, item) item
    heapreplace|heapreplace(heap, item) item
    nlargest|nlargest(n, iterable) list|list
    nsmallest|nsmallest(n, iterable) list|list`),
  bisect: members('function', `
    bisect_left|bisect_left(a, x) int|int
    bisect_right|bisect_right(a, x) int|int
    bisect|bisect(a, x) int|int
    insort|insort(a, x) None
    insort_left|insort_left(a, x) None`),
  collections: members('function', `
    deque|deque(iterable=(), maxlen=None) deque|deque
    Counter|Counter(iterable) Counter|Counter
    defaultdict|defaultdict(factory) defaultdict|defaultdict
    OrderedDict|OrderedDict() dict|dict
    namedtuple|namedtuple(name, fields) type`),
  itertools: members('function', `
    permutations|permutations(iterable, r) iterator
    combinations|combinations(iterable, r) iterator
    combinations_with_replacement|combinations_with_replacement(iterable, r) iterator
    product|product(*iterables, repeat=1) iterator
    accumulate|accumulate(iterable) iterator
    chain|chain(*iterables) iterator
    groupby|groupby(iterable, key) iterator
    count|count(start) iterator`),
  functools: members('function', `
    reduce|reduce(function, iterable) value
    lru_cache|lru_cache(maxsize=None) decorator
    cache|cache decorator
    cmp_to_key|cmp_to_key(cmp) key`),
  string: members('function', `
    ascii_lowercase|ascii_lowercase str|str
    ascii_uppercase|ascii_uppercase str|str
    digits|digits str|str`),
};
for (const module of Object.values(pyModules)) for (const m of module) if (!m.signature.includes('(')) m.kind = 'variable';

// ---- Receiver parsing -------------------------------------------------------------------------------
const identifierChar = /[\p{ID_Continue}$]/u;
function skipBalanced(text, end, open, close) {
  let depth = 0;
  for (let i = end; i >= 0; i--) {
    if (text[i] === close) depth++;
    else if (text[i] === open && --depth === 0) return i;
  }
  return -1;
}
/** Segments of the expression ending right before `dot`, e.g. br.readLine() -> [{name:'br'},{name:'readLine',call:true}]. */
export function receiverSegments(text, dot) {
  const segments = [];
  let i = dot - 1;
  while (i >= 0 && text[i] === ' ') i--;
  for (;;) {
    const segment = { name: '', call: false, index: 0 };
    while (i >= 0 && (text[i] === ')' || text[i] === ']')) {
      if (text[i] === ']') { const start = skipBalanced(text, i, '[', ']'); if (start < 0) return null; segment.index++; i = start - 1; }
      else { if (segment.call || segment.index) return null; const start = skipBalanced(text, i, '(', ')'); if (start < 0) return null; segment.call = true; i = start - 1; }
      while (i >= 0 && text[i] === ' ') i--;
    }
    if (i >= 0 && (text[i] === '"' || text[i] === "'")) {
      const quote = text[i]; let start = i - 1;
      while (start >= 0 && !(text[start] === quote && text[start - 1] !== '\\')) start--;
      if (start < 0) return null;
      segments.unshift({ ...segment, literal: 'string' });
      return segments;
    }
    let end = i;
    while (i >= 0 && identifierChar.test(text[i])) i--;
    segment.name = text.slice(i + 1, end + 1);
    if (!segment.name || /^\d/.test(segment.name)) return null;
    segments.unshift(segment);
    while (i >= 0 && text[i] === ' ') i--;
    if (i >= 0 && text[i] === '.') { i--; while (i >= 0 && text[i] === ' ') i--; continue; }
    if (i >= 1 && text[i] === ':' && text[i - 1] === ':') return segments; // std::cin: the namespace is not a receiver
    return segments;
  }
}

// ---- Declarations ------------------------------------------------------------------------------------
const javaNotTypes = new Set(['return','new','throw','else','case','instanceof','import','package','extends','implements','throws','assert','yield','break','continue','do','this','super']);
const escape = name => name.replace(/[$]/g, '\\$');
function javaDeclared(code, name) {
  const pattern = new RegExp(`([A-Za-z_$][\\w$.]*(?:\\s*<[^;(){}=]*?>)?(?:\\s*\\[\\s*\\])*)\\s+(?:[\\w$]+(?:\\s*\\[\\s*\\])*\\s*(?:=[^;,{}]*)?,\\s*)*${escape(name)}\\s*((?:\\[\\s*\\]\\s*)*)(?=[=;,):])`, 'g');
  let found = null;
  for (const match of code.matchAll(pattern)) {
    const type = match[1].trim();
    if (javaNotTypes.has(type.split(/[<\s[]/)[0])) continue;
    found = type + '[]'.repeat((match[2].match(/\[/g) || []).length); // int arr[] style
  }
  return found && parseType(found);
}
const cppNotTypes = new Set(['return','else','case','delete','new','throw','using','namespace','typedef','goto','sizeof','co_return']);
function cppDeclared(code, name) {
  const pattern = new RegExp(`((?:const\\s+)?(?:std::)?[A-Za-z_]\\w*(?:\\s*<[^;(){}=]*?>)?)\\s*[&*]?\\s+(?:[A-Za-z_]\\w*(?:\\s*\\[[^\\]]*\\])*\\s*(?:=[^;,{}]*|\\{[^;{}]*\\}|\\([^;()]*\\))?\\s*,\\s*)*${name}\\s*(\\[[^\\]]*\\])*\\s*(?=[=;,()\\{:])`, 'g');
  let found = null;
  for (const match of code.matchAll(pattern)) {
    const type = match[1].trim();
    if (cppNotTypes.has(type) || type === 'auto') { found = null; continue; }
    const dims = (match[2] || '').match(/\[/g)?.length || 0;
    let parsed = parseType(type);
    for (let i = 0; i < dims; i++) parsed = { base: '[]', args: [parsed] };
    found = parsed;
  }
  return found;
}
function pythonAssigned(code, name) {
  const pattern = new RegExp(`(?:^|\\n)[ \\t]*${name}\\s*(?::\\s*([A-Za-z_][\\w\\[\\], ]*))?\\s*=(?!=)\\s*([^\\n]*)`, 'g');
  let found = null;
  for (const match of code.matchAll(pattern)) {
    if (match[1]) { found = parseType(match[1].replace(/\[/, '<').replace(/\]$/, '>')); continue; }
    found = pythonExpressionType(match[2].trim(), code);
  }
  return found;
}
function pythonExpressionType(expr, code) {
  expr = expr.replace(/\s+#.*$/, '');
  if (!expr) return null;
  if (/^(r|b|f)?["']/.test(expr) && !/\.split\(|\.splitlines\(/.test(expr)) return T('str');
  if (expr.startsWith('[')) return T('list');
  if (expr.startsWith('(')) return T('tuple');
  if (expr.startsWith('{')) return /^\{\s*\}$/.test(expr) || /^\{[^{}]*:/.test(expr) ? T('dict') : T('set');
  if (/^-?\d+$/.test(expr)) return T('int');
  const call = expr.match(/^([\w.]+)\s*\(/);
  if (call) {
    const segments = receiverSegments(expr + '.', expr.length);
    if (segments) {
      const resolved = resolve('PYTHON', segments, code);
      if (resolved) return resolved;
    }
  }
  return null;
}
const pyBuiltins = { input:'str', str:'str', list:'list', sorted:'list', dict:'dict', set:'set', tuple:'tuple', int:'int', len:'int', sum:'int', ord:'int', chr:'str',
  deque:'deque', Counter:'Counter', defaultdict:'defaultdict', repr:'str', bin:'str', hex:'str', abs:'int', max:null, min:null };

// ---- Resolution -------------------------------------------------------------------------------------
function catalogFor(language, type) {
  if (!type) return null;
  if (language === 'JAVA') return java[type.base] || null;
  if (language === 'CPP') return cpp[type.base] || null;
  return python[type.base.replace(/^list$/, 'list')] || null;
}
function paramsFor(language, base) {
  return (language === 'JAVA' ? typeParams : language === 'CPP' ? cppParams : { list:['T'], deque:['T'], dict:['K','V'], set:['T'] })[base] || [];
}
function returnType(language, owner, member) {
  const r = member.returns;
  if (!r) return null;
  if (r === 'SELF') return owner;
  if (r === 'NUM') return null;
  const params = paramsFor(language, owner.base), bind = new Map(params.map((p, i) => [p, owner.args[i]]));
  if (owner.base === '[]' || (language === 'CPP' && owner.base === 'string')) bind.set('T', owner.args[0] || T('char'));
  const substitute = type => {
    if (bind.has(type.base) && !type.args.length) return bind.get(type.base) || null;
    return { base: type.base, args: type.args.map(a => substitute(a) || T('Object')) };
  };
  return substitute(parseType(r.replace(/list\[str\]/, 'list<str>')));
}
function elementType(language, type) {
  if (!type) return null;
  if (type.base === '[]') return type.args[0] || null;
  if (language === 'CPP' && type.base === 'string') return T('char');
  if (language === 'CPP' && ['vector','deque'].includes(type.base)) return type.args[0] || null;
  if (language === 'CPP' && ['map','unordered_map'].includes(type.base)) return type.args[1] || null;
  if (language === 'PYTHON' && type.base === 'str') return T('str');
  if (language === 'PYTHON' && ['list','deque'].includes(type.base)) return type.args[0] || null;
  if (language === 'PYTHON' && ['dict','defaultdict','Counter'].includes(type.base)) return type.args[1] || null;
  return null;
}
function declared(language, code, name) {
  return language === 'JAVA' ? javaDeclared(code, name) : language === 'CPP' ? cppDeclared(code, name) : pythonAssigned(code, name);
}
/** Type of the receiver chain, or a static/module marker; null when unknown. */
export function resolve(language, segments, code) {
  if (!segments?.length) return null;
  const [first, ...rest] = segments;
  let type = null;
  if (first.literal === 'string') type = T(language === 'JAVA' ? 'String' : language === 'CPP' ? 'string' : 'str');
  else if (language === 'PYTHON' && first.call) {
    const own = pyBuiltins[first.name];
    type = own ? T(own) : null;
  } else {
    type = declared(language, code, first.name);
    if (!type && !first.call) {
      if (language === 'JAVA' && javaStatics[first.name]) type = { base: `static:${first.name}`, args: [] };
      else if (language === 'PYTHON' && pyModules[first.name] && !new RegExp(`(?:^|\\n)\\s*${first.name}\\s*=`).test(code)) type = { base: `module:${first.name}`, args: [] };
      else if (language === 'PYTHON' && first.name === 'stdin') type = T('TextIO');
      else if (language === 'CPP' && cppGlobals[first.name]) type = T(cppGlobals[first.name]);
    }
    if (first.call && language !== 'PYTHON') type = null; // local method return types are not inferred
  }
  for (let n = 0; n < first.index && type; n++) type = elementType(language, type);
  for (const segment of rest) {
    if (!type) return null;
    const catalog = type.base.startsWith('static:') ? javaStatics[type.base.slice(7)] : type.base.startsWith('module:') ? pyModules[type.base.slice(7)] : catalogFor(language, type);
    const member = catalog?.find(m => m.label === segment.name);
    if (!member) return null;
    const isCall = member.signature.includes('(');
    if (isCall !== segment.call) return null; // e.g. br.readLine. or a field used as a call
    type = type.base.startsWith('static:') || type.base.startsWith('module:') ? (member.returns ? parseType(member.returns) : null) : returnType(language, type, member);
    for (let n = 0; n < segment.index && type; n++) type = elementType(language, type);
  }
  return type;
}

const kindNames = { method:'method', function:'function', property:'property', variable:'variable' };
/** Completion result for text/pos, or null when the cursor is not after "receiver." or the type is unknown. */
export function memberCompletions(language, text, pos) {
  let from = pos;
  while (from > 0 && identifierChar.test(text[from - 1])) from--;
  let dot = from - 1;
  const arrow = language === 'CPP' && text.slice(from - 2, from) === '->';
  if (!arrow && text[dot] !== '.') return null;
  if (arrow) return null; // pointer members are not resolved
  if (/\d/.test(text[dot - 1] || '') && !identifierChar.test(text[dot - 2] || ' ')) return null; // 1.5
  const segments = receiverSegments(text, dot);
  if (!segments) return null;
  const code = text.slice(0, dot);
  const type = resolve(language, segments, code);
  if (!type) return null;
  const catalog = type.base.startsWith('static:') ? javaStatics[type.base.slice(7)] : type.base.startsWith('module:') ? pyModules[type.base.slice(7)] : catalogFor(language, type);
  if (!catalog) return null;
  const seen = new Map();
  const extra = language === 'JAVA' && !type.base.startsWith('static:') && type.base !== '[]' ? objectMembers : [];
  for (const m of [...catalog, ...extra]) if (!seen.has(m.label)) seen.set(m.label, m);
  const shown = type.base.startsWith('static:') ? type.base.slice(7) : type.base.startsWith('module:') ? type.base.slice(7) : formatType(type);
  return { from, options: [...seen.values()].map(m => ({ label: m.label, type: kindNames[m.kind] || 'method', detail: m.signature, info: `${shown} 멤버` })) };
}
export function formatType(type) {
  if (!type) return '';
  if (type.base === '[]') return `${formatType(type.args[0])}[]`;
  return type.args.length ? `${type.base}<${type.args.map(formatType).join(', ')}>` : type.base;
}
