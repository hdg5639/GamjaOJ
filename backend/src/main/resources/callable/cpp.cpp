#include <bits/stdc++.h>
namespace gamja_callable {
struct Value { int kind=0; long long number=0; bool boolean=false; std::string text; std::vector<Value> array; };
inline void require(bool ok){if(!ok)throw std::runtime_error("invalid callable value");}
inline void utf8(std::string& s,unsigned n){if(n<128)s+=char(n);else if(n<2048){s+=char(192|(n>>6));s+=char(128|(n&63));}else if(n<65536){s+=char(224|(n>>12));s+=char(128|((n>>6)&63));s+=char(128|(n&63));}else{s+=char(240|(n>>18));s+=char(128|((n>>12)&63));s+=char(128|((n>>6)&63));s+=char(128|(n&63));}}
struct Parser {
 std::string s; size_t p=0;
 void ws(){while(p<s.size()&&std::string(" \r\n\t").find(s[p])!=std::string::npos)++p;}
 char take(){require(p<s.size());return s[p++];}
 unsigned hex(){require(p+4<=s.size());unsigned n=0;for(int i=0;i<4;i++){char c=take();require(std::isxdigit((unsigned char)c));n=n*16+(c<='9'?c-'0':std::tolower(c)-'a'+10);}return n;}
 Value value(int depth=0){require(depth<=8);ws();char c=take();Value v;
 if(c=='['){v.kind=4;ws();if(p<s.size()&&s[p]==']'){p++;return v;}while(true){v.array.push_back(value(depth+1));ws();c=take();if(c==']')return v;require(c==',');}}
 if(c=='"'){v.kind=3;while(true){c=take();if(c=='"')return v;require((unsigned char)c>=32);if(c=='\\'){c=take();if(c=='u'){unsigned n=hex();if(n>=0xd800&&n<=0xdbff&&p+6<=s.size()&&s.substr(p,2)=="\\u"){size_t old=p;p+=2;unsigned low=hex();if(low>=0xdc00&&low<=0xdfff)n=0x10000+((n-0xd800)<<10)+(low-0xdc00);else p=old;}utf8(v.text,n);continue;}if(c=='n')c='\n';else if(c=='r')c='\r';else if(c=='t')c='\t';else if(c=='b')c='\b';else if(c=='f')c='\f';else require(c=='"'||c=='\\'||c=='/');}v.text+=c;}}
 if(c=='t'||c=='f'){std::string token=c=='t'?"true":"false";require(s.substr(p-1,token.size())==token);p+=token.size()-1;v.kind=2;v.boolean=c=='t';return v;}
 require(c=='-'||(c>='0'&&c<='9'));size_t start=p-1;while(p<s.size()&&s[p]>='0'&&s[p]<='9')p++;std::string n=s.substr(start,p-start);size_t i=n[0]=='-'?1:0;require(i<n.size()&&(n[i]!='0'||n.size()==i+1));size_t used;v.number=std::stoll(n,&used);require(used==n.size());v.kind=1;return v;
 }
 Value parse(){auto v=value();ws();require(p==s.size());return v;}
};
template<class T> T convert(const Value& v);
template<> inline int convert<int>(const Value& v){require(v.kind==1&&v.number>=INT_MIN&&v.number<=INT_MAX);return int(v.number);}
template<> inline long long convert<long long>(const Value& v){require(v.kind==1);return v.number;}
template<> inline bool convert<bool>(const Value& v){require(v.kind==2);return v.boolean;}
template<> inline std::string convert<std::string>(const Value& v){require(v.kind==3);return v.text;}
template<class T> std::vector<T> array(const Value& v){require(v.kind==4);std::vector<T> out;out.reserve(v.array.size());for(const auto& x:v.array)out.push_back(convert<T>(x));return out;}
inline void escape(std::ostream& o,unsigned n){const char* h="0123456789abcdef";o<<"\\u"<<h[(n>>12)&15]<<h[(n>>8)&15]<<h[(n>>4)&15]<<h[n&15];}
inline void output(std::ostream& o,const std::string& s){o<<'"';for(size_t i=0;i<s.size();){unsigned n=(unsigned char)s[i++];if(n>=128){int count=n<224?1:n<240?2:3;n&=count==1?31:count==2?15:7;while(count--){require(i<s.size()&&((unsigned char)s[i]&192)==128);n=(n<<6)|((unsigned char)s[i++]&63);}require(n<=0x10ffff);}if(n<=32||(n>=0xd800&&n<=0xdfff))escape(o,n);else if(n>65535){n-=65536;escape(o,0xd800+(n>>10));escape(o,0xdc00+(n&1023));}else if(n=='"'||n=='\\')o<<'\\'<<char(n);else {std::string x;utf8(x,n);o<<x;}}o<<'"';}
inline void output(std::ostream& o,bool v){o<<(v?"true":"false");}
inline void output(std::ostream& o,int v){o<<v;}
inline void output(std::ostream& o,long long v){o<<v;}
template<class T> void output(std::ostream& o,const std::vector<T>& v){o<<'[';for(size_t i=0;i<v.size();i++){if(i)o<<',';output(o,T(v[i]));}o<<']';}
}
