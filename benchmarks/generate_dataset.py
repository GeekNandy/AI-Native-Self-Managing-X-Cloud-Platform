"""Rebuild the public synthetic repair corpus and its test-only reference patches."""
import json
from pathlib import Path

rows=[]
def case(identifier, split, category, instruction, bad, good, assertions):
    imports='import java.util.*; import java.math.*; import java.time.*; import java.net.*; import java.nio.charset.*;\n'
    source=imports+'public class Rules { '+bad+' }\n'
    fixed=imports+'public class Rules { '+good+' }\n'
    tests=imports+'''public class RulesTest {
 static void check(boolean ok) { if(!ok) throw new AssertionError("Regression contract violated"); }
 static void reject(Runnable r) { try { r.run(); } catch(IllegalArgumentException expected) { return; } throw new AssertionError("Invalid input was accepted"); }
 public static void main(String[] args) { '''+assertions+''' System.out.println("Regression checks passed"); }
}\n'''
    rows.append((dict(id=identifier,split=split,category=category,instruction=instruction,files={'src/main/java/Rules.java':source,'src/test/java/RulesTest.java':tests},testClass='RulesTest'),fixed))

case('dev-clamp','development','bounds','Clamp an integer to an inclusive lower/upper range.',
 'static int clamp(int x,int lo,int hi){return Math.min(lo,Math.max(hi,x));}',
 'static int clamp(int x,int lo,int hi){return Math.max(lo,Math.min(hi,x));}',
 'check(Rules.clamp(5,0,10)==5);check(Rules.clamp(-2,0,10)==0);check(Rules.clamp(20,0,10)==10);')
case('dev-null','development','nullability','Normalize optional text: null is empty, other strings are trimmed.',
 'static String normalize(String s){return s.trim();}',
 'static String normalize(String s){return s==null?"":s.trim();}',
 'check(Rules.normalize(null).equals(""));check(Rules.normalize(" x ").equals("x"));')
case('dev-boundary','development','validation','Eligibility starts at age 18, including the boundary.',
 'static boolean eligible(int age){return age>18;}', 'static boolean eligible(int age){return age>=18;}',
 'check(Rules.eligible(18));check(!Rules.eligible(17));check(Rules.eligible(30));')
case('dev-sum','development','aggregation','Sum all supplied integers; the empty sum is zero.',
 'static int sum(List<Integer> xs){return xs.stream().reduce(1,Integer::sum);}',
 'static int sum(List<Integer> xs){return xs.stream().reduce(0,Integer::sum);}',
 'check(Rules.sum(List.of())==0);check(Rules.sum(List.of(2,3,-1))==4);')
case('dev-discount','development','arithmetic','Subtract a five-unit discount, rejecting negative input.',
 'static int price(int n){if(n<0)throw new IllegalArgumentException();return n+5;}',
 'static int price(int n){if(n<0)throw new IllegalArgumentException();return n-5;}',
 'check(Rules.price(12)==7);check(Rules.price(5)==0);reject(()->Rules.price(-1));')
case('eval-mean','evaluation','overflow','Compute the mean of two integers without overflow.',
 'static double mean(int a,int b){return (a+b)/2.0;}',
 'static double mean(int a,int b){return a/2.0+b/2.0;}',
 'check(Rules.mean(Integer.MAX_VALUE,Integer.MAX_VALUE)==Integer.MAX_VALUE);check(Rules.mean(2,3)==2.5);check(Rules.mean(Integer.MIN_VALUE,Integer.MIN_VALUE)==Integer.MIN_VALUE);')
case('eval-unicode','evaluation','unicode','Count Unicode code points, not UTF-16 code units.',
 'static int length(String s){return s.length();}',
 'static int length(String s){return s.codePointCount(0,s.length());}',
 'check(Rules.length("a😀b")==3);check(Rules.length("")==0);check(Rules.length("abc")==3);')
case('eval-locale','evaluation','locale','Canonical keys must lowercase consistently using the root locale.',
 'static String key(String s){return s.toLowerCase(Locale.forLanguageTag("tr"));}',
 'static String key(String s){return s.toLowerCase(Locale.ROOT);}',
 'check(Rules.key("ID").equals("id"));check(Rules.key("Hello").equals("hello"));')
case('eval-unique','evaluation','collections','Remove duplicates while preserving first occurrence order.',
 'static List<String> unique(List<String> xs){return new ArrayList<>(new TreeSet<>(xs));}',
 'static List<String> unique(List<String> xs){return new ArrayList<>(new LinkedHashSet<>(xs));}',
 'check(Rules.unique(List.of("b","a","b","c")).equals(List.of("b","a","c")));check(Rules.unique(List.of()).isEmpty());')
case('eval-copy','evaluation','immutability','Return a sorted copy without mutating or requiring mutable input.',
 'static List<Integer> sorted(List<Integer> xs){xs.sort(Integer::compare);return xs;}',
 'static List<Integer> sorted(List<Integer> xs){var copy=new ArrayList<>(xs);copy.sort(Integer::compare);return copy;}',
 'var input=new ArrayList<>(List.of(3,1,2));check(Rules.sorted(input).equals(List.of(1,2,3)));check(input.equals(List.of(3,1,2)));check(Rules.sorted(List.of(2,1)).equals(List.of(1,2)));')
case('eval-page','evaluation','pagination','Return the requested zero-based page with positive page size.',
 'static List<String> page(List<String> xs,int page,int size){if(page<0||size<=0)throw new IllegalArgumentException();return xs.stream().skip(page).limit(size).toList();}',
 'static List<String> page(List<String> xs,int page,int size){if(page<0||size<=0)throw new IllegalArgumentException();return xs.stream().skip((long)page*size).limit(size).toList();}',
 'check(Rules.page(List.of("a","b","c","d","e"),1,2).equals(List.of("c","d")));check(Rules.page(List.of("a"),2,2).isEmpty());reject(()->Rules.page(List.of(),-1,2));')
case('eval-leap','evaluation','calendar','Implement Gregorian leap-year rules including century exceptions.',
 'static boolean leap(int year){return year%4==0;}',
 'static boolean leap(int year){return year%4==0&&(year%100!=0||year%400==0);}',
 'check(!Rules.leap(1900));check(Rules.leap(2000));check(Rules.leap(2024));check(!Rules.leap(2023));')
case('eval-expiry','evaluation','time','An entry is expired at or after its expiration instant.',
 'static boolean expired(long now,long until){return now>until;}',
 'static boolean expired(long now,long until){return now>=until;}',
 'check(Rules.expired(100,100));check(Rules.expired(101,100));check(!Rules.expired(99,100));')
case('eval-round','evaluation','decimal','Round currency to two decimal places using HALF_UP.',
 'static BigDecimal money(String s){return new BigDecimal(s).setScale(2,RoundingMode.DOWN);}',
 'static BigDecimal money(String s){return new BigDecimal(s).setScale(2,RoundingMode.HALF_UP);}',
 'check(Rules.money("1.235").toPlainString().equals("1.24"));check(Rules.money("1.234").toPlainString().equals("1.23"));check(Rules.money("-1.235").toPlainString().equals("-1.24"));')
case('eval-url','evaluation','encoding','Encode a UTF-8 query value using percent escapes, including spaces as %20.',
 'static String encode(String s){return URLEncoder.encode(s,StandardCharsets.UTF_8);}',
 'static String encode(String s){return URLEncoder.encode(s,StandardCharsets.UTF_8).replace("+","%20");}',
 'check(Rules.encode("a b").equals("a%20b"));check(Rules.encode("é+").equals("%C3%A9%2B"));')
case('eval-unsigned','evaluation','numeric','Compare 32-bit integers as unsigned values.',
 'static int compare(int a,int b){return Integer.compare(a,b);}',
 'static int compare(int a,int b){return Integer.compareUnsigned(a,b);}',
 'check(Rules.compare(-1,1)>0);check(Rules.compare(0,-1)<0);check(Rules.compare(1,1)==0);')
case('eval-hex','evaluation','parsing','Parse hexadecimal input and reject invalid digits.',
 'static int hex(String s){return Integer.parseInt(s,10);}',
 'static int hex(String s){return Integer.parseInt(s,16);}',
 'check(Rules.hex("ff")==255);check(Rules.hex("10")==16);reject(()->Rules.hex("zz"));')
case('eval-factorial','evaluation','iteration','Compute factorial for nonnegative inputs up to 12, including zero.',
 'static int factorial(int n){if(n<0||n>12)throw new IllegalArgumentException();int r=1;for(int i=2;i<n;i++)r*=i;return r;}',
 'static int factorial(int n){if(n<0||n>12)throw new IllegalArgumentException();int r=1;for(int i=2;i<=n;i++)r*=i;return r;}',
 'check(Rules.factorial(0)==1);check(Rules.factorial(5)==120);reject(()->Rules.factorial(-1));')
case('eval-path','evaluation','paths','Accept relative POSIX paths whose normalized form stays within a root; reject absolute paths and traversal outside it.',
 'static boolean safe(String s){return !s.contains("..");}',
 'static boolean safe(String s){var p=java.nio.file.Path.of(s);return !p.isAbsolute()&&!p.normalize().startsWith("..");}',
 'check(Rules.safe("data/x"));check(Rules.safe("a/../b"));check(!Rules.safe("../../x"));check(!Rules.safe("/etc/x"));')
case('eval-csv','evaluation','state-machine','Parse a CSV record with quoted commas and doubled quotes; reject an unterminated quoted field.',
 'static List<String> csv(String s){return Arrays.asList(s.split(",",-1));}',
 r'''static List<String> csv(String s){var out=new ArrayList<String>();var b=new StringBuilder();boolean quoted=false;for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c=='"'){if(quoted&&i+1<s.length()&&s.charAt(i+1)=='"'){b.append('"');i++;}else quoted=!quoted;}else if(c==','&&!quoted){out.add(b.toString());b.setLength(0);}else b.append(c);}if(quoted)throw new IllegalArgumentException();out.add(b.toString());return out;}''',
 r'''check(Rules.csv("alpha,\"b,c\",d").equals(List.of("alpha","b,c","d")));check(Rules.csv("\"a\"\"b\",c").equals(List.of("a\"b","c")));check(Rules.csv("a,").equals(List.of("a","")));reject(()->Rules.csv("\"bad"));''')
case('eval-backoff','evaluation','saturation','Compute capped exponential backoff without long overflow; invalid negative inputs must be rejected.',
 'static long backoff(long base,int attempt,long cap){if(base<0||attempt<0||cap<0)throw new IllegalArgumentException();return Math.min(cap,base<<attempt);}',
 'static long backoff(long base,int attempt,long cap){if(base<0||attempt<0||cap<0)throw new IllegalArgumentException();if(base==0)return 0;long value=base;for(int i=0;i<attempt&&value<cap;i++){if(value>cap/2)return cap;value*=2;}return Math.min(cap,value);}',
 'check(Rules.backoff(2,3,100)==16);check(Rules.backoff(2,63,100)==100);check(Rules.backoff(0,1000000,100)==0);reject(()->Rules.backoff(-1,2,10));')
case('eval-first','evaluation','conflict-resolution','Index the first occurrence of each key; later duplicates must not overwrite it.',
 'static Map<String,Integer> first(List<String> keys){var result=new LinkedHashMap<String,Integer>();for(int i=0;i<keys.size();i++)result.put(keys.get(i),i);return result;}',
 'static Map<String,Integer> first(List<String> keys){var result=new LinkedHashMap<String,Integer>();for(int i=0;i<keys.size();i++)result.putIfAbsent(keys.get(i),i);return result;}',
 'check(Rules.first(List.of("x","y","x")).equals(Map.of("x",0,"y",1)));check(Rules.first(List.of()).isEmpty());')
case('eval-interval','evaluation','intervals','Detect overlap of nonempty half-open intervals; touching endpoints do not overlap.',
 'static boolean overlap(int a,int b,int c,int d){return a<=d&&c<=b;}',
 'static boolean overlap(int a,int b,int c,int d){return a<b&&c<d&&a<d&&c<b;}',
 'check(!Rules.overlap(0,2,2,4));check(Rules.overlap(0,3,2,4));check(!Rules.overlap(1,1,0,2));')
case('eval-median','evaluation','statistics','Find the median without mutating input, averaging the two middle values for even lengths.',
 'static double median(int[] xs){if(xs.length==0)throw new IllegalArgumentException();return xs[xs.length/2];}',
 'static double median(int[] xs){if(xs.length==0)throw new IllegalArgumentException();var copy=xs.clone();Arrays.sort(copy);int m=copy.length/2;return copy.length%2==1?copy[m]:copy[m-1]/2.0+copy[m]/2.0;}',
 'int[] xs={5,1,3};check(Rules.median(xs)==3);check(Arrays.equals(xs,new int[]{5,1,3}));check(Rules.median(new int[]{4,1,2,3})==2.5);reject(()->Rules.median(new int[]{}));')
case('eval-boolean','evaluation','strict-validation','Parse trimmed case-insensitive true/false text and reject any other value.',
 'static boolean bool(String s){return Boolean.parseBoolean(s);}',
 'static boolean bool(String s){return switch(s.trim().toLowerCase(Locale.ROOT)){case "true"->true;case "false"->false;default->throw new IllegalArgumentException();};}',
 'check(Rules.bool(" TRUE "));check(!Rules.bool("false"));reject(()->Rules.bool("yes"));')

root=Path(__file__).resolve().parents[1]
assert len(rows)==25
(root/'src/main/resources/evaluation/repair-v1.json').write_text(json.dumps(dict(version='repair-v1',harness='java21-main-v1',tasks=[r[0] for r in rows]),indent=2,ensure_ascii=False)+'\n')
(root/'benchmarks/repair-v1-oracles.json').write_text(json.dumps({r[0]['id']:r[1] for r in rows},indent=2,ensure_ascii=False)+'\n')
