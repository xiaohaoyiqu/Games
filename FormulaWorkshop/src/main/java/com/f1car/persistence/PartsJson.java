package com.f1car.persistence;

import com.f1car.model.PartsCatalog;
import com.f1car.persistence.PartsImportException.Code;
import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.nio.charset.*;
import java.util.*;

/** Bounded JSON syntax and field access shared by parts files; no reflection-based object loading. */
final class PartsJson {
    private PartsJson() {}
    record Node(JsonElement value,String pointer) {
        Node child(String key) {return new Node(value!=null && value.isJsonObject()?value.getAsJsonObject().get(key):null,PartsImportException.child(pointer,key));}
        Node index(int index) {return new Node(value.getAsJsonArray().get(index),PartsImportException.child(pointer,Integer.toString(index)));}
        PartsImportException error(Code code,String detail,String hint) {return new PartsImportException(code,pointer,detail,hint);}
        Node object(String... keys) throws IOException {
            if(value==null || !value.isJsonObject()) throw error(Code.TYPE,"这里需要 JSON 对象。","请核对模板中的大括号和字段结构。");var wanted=new LinkedHashSet<>(Arrays.asList(keys));
            for(String key:keys) if(!value.getAsJsonObject().has(key)) throw child(key).error(Code.STRUCTURE,"缺少必填字段。","请从当前格式模板补齐该字段。");
            for(String key:new TreeSet<>(value.getAsJsonObject().keySet())) if(!wanted.contains(key)) throw child(key).error(Code.STRUCTURE,"当前格式不支持此字段。","请检查拼写，并仅使用该槽位支持的字段。");return this;
        }
        JsonArray array(int min,int max) throws IOException {if(value==null || !value.isJsonArray()) throw error(Code.TYPE,"这里需要 JSON 数组。","请使用方括号列出条目。");var array=value.getAsJsonArray();if(array.size()<min || array.size()>max) throw error(Code.LIMIT,"条目数量应为 "+min+"–"+max+"。","请按容量拆分或补齐文件。");return array;}
        String text(int max,boolean nonblank) throws IOException {if(value==null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw error(Code.TYPE,"这里需要文本。","请使用双引号包围文本。");String text=value.getAsString();if(!PartsCatalog.text(text,max) || nonblank && text.isBlank()) throw error(Code.RANGE,"文本应"+(nonblank?"非空、":"")+"最多 "+max+" 个 UTF-16 单元，且无控制字符或不完整字符。","请缩短文本或移除换行、制表等控制字符。");return text;}
        int integer(int min,int max) throws IOException {if(value==null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw error(Code.TYPE,"这里需要整数，不能使用字符串数字。","百分数使用 105 这样的整数。");var decimal=value.getAsBigDecimal();if(decimal.compareTo(java.math.BigDecimal.valueOf(min))<0 || decimal.compareTo(java.math.BigDecimal.valueOf(max))>0) throw error(Code.RANGE,"数值应在 "+min+"–"+max+" 之间。","请核对模型边界或该部件的 min／max。");try {decimal=decimal.stripTrailingZeros();if(decimal.scale()>0) throw error(Code.RANGE,"数值应为有限整数。","移除小数部分，使用支持的整数范围。");return decimal.intValueExact();}catch(ArithmeticException invalid) {throw error(Code.RANGE,"数值无法表示为支持的整数。","使用该字段支持的小整数。");}}
        String partId() throws IOException {String id=text(81,true);if(!PartsCatalog.validId(id)) throw error(Code.IDENTITY,"部件身份格式无效。","使用 包id:本地id，只含小写字母、数字和连字符。");return id;}
        String token() throws IOException {String text=text(96,true);if(!text.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,95}")) throw error(Code.IDENTITY,"车型或物理身份格式无效。","保留游戏导出的车型与物理身份。");return text;}
        <T extends Enum<T>> T enumeration(Class<T> type) throws IOException {String text=text(32,true);try {return Enum.valueOf(type,text);}catch(IllegalArgumentException error) {throw error(Code.TYPE,"不支持这个类别。","可用值："+Arrays.toString(type.getEnumConstants()));}}
    }
    static Node read(InputStream stream,int limit) throws IOException {
        return read(stream,limit,10);
    }
    static Node read(InputStream stream,int limit,int maximumDepth) throws IOException {
        return read(stream.readNBytes(limit+1),limit,maximumDepth);
    }
    static Node read(byte[] bytes,int limit,int maximumDepth) throws IOException {
        if(bytes.length>limit) throw new PartsImportException(Code.LIMIT,"","文件超过 "+limit+" 字节上限。","请拆分内容或移除不必要的描述。");String value;
        try {value=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();}catch(CharacterCodingException error) {throw new PartsImportException(Code.ENCODING,"","文件不是有效的 UTF-8。","在文本编辑器中另存为 UTF-8。");}
        return readDecoded(value,maximumDepth);
    }
    /** Use after enforcing the file's byte limit and validating its UTF-8. */
    static Node readDecoded(String value,int maximumDepth) throws IOException {
        if(maximumDepth<1 || maximumDepth>32) throw new IllegalArgumentException("Invalid JSON depth bound");
        try(var reader=new JsonReader(new StringReader(value))) {reader.setStrictness(Strictness.STRICT);var result=readValue(reader,0,"",maximumDepth);if(reader.peek()!=JsonToken.END_DOCUMENT) throw new PartsImportException(Code.SYNTAX,"","根对象后还有额外内容。","一个文件只保留一个根 JSON 对象。");return new Node(result,"");}catch(PartsImportException error) {throw error;}catch(IOException error) {throw syntax("",error);}
    }
    private static PartsImportException syntax(String pointer,IOException error) {var match=java.util.regex.Pattern.compile("line (\\d+) column (\\d+)").matcher(Objects.toString(error.getMessage(),""));String location=match.find()?"第 "+match.group(1)+" 行，第 "+match.group(2)+" 列。":"";return new PartsImportException(Code.SYNTAX,pointer,"JSON 语法无效。"+location,"检查逗号、双引号、括号和文件结尾。");}
    private static boolean arrayHasNext(JsonReader reader,String pointer) throws IOException {try {return reader.hasNext();}catch(IOException error) {throw syntax(pointer,error);}}
    static Node subtree(JsonReader reader,String pointer,int maximumDepth) throws IOException {if(maximumDepth<1 || maximumDepth>32) throw new IllegalArgumentException("Invalid JSON depth bound");return new Node(readValue(reader,0,pointer,maximumDepth),pointer);}
    private static JsonElement readValue(JsonReader reader,int depth,String pointer,int maximumDepth) throws IOException {
        if(depth>maximumDepth) throw new PartsImportException(Code.LIMIT,pointer,"嵌套超过 "+maximumDepth+" 层。","请使用格式模板中的结构。");
        try {return switch(reader.peek()) {
            case BEGIN_OBJECT -> {reader.beginObject();var result=new JsonObject();while(reader.hasNext()) {String key=reader.nextName();if(!PartsCatalog.text(key,100)) throw new PartsImportException(Code.LIMIT,pointer,"字段名过长，或包含控制字符／不完整字符。","字段名最多 100 单元且应与模板一致。");String child=PartsImportException.child(pointer,key);if(result.has(key)) throw new PartsImportException(Code.DUPLICATE,child,"JSON 字段重复。","同一个对象内每个字段只填写一次。");if(result.size()>=32) throw new PartsImportException(Code.LIMIT,pointer,"对象超过 32 个字段。","请核对模板，移除不支持的字段。");result.add(key,readValue(reader,depth+1,child,maximumDepth));}reader.endObject();yield result;}
            case BEGIN_ARRAY -> {reader.beginArray();var result=new JsonArray();while(arrayHasNext(reader,PartsImportException.child(pointer,Integer.toString(result.size())))) {String child=PartsImportException.child(pointer,Integer.toString(result.size()));if(result.size()>=128) throw new PartsImportException(Code.LIMIT,child,"数组超过 128 项。","请按格式容量拆分内容。");result.add(readValue(reader,depth+1,child,maximumDepth));}reader.endArray();yield result;}
            case STRING -> {String text=reader.nextString();if(text.length()>4096) throw new PartsImportException(Code.LIMIT,pointer,"文本令牌过长。","单段文本最多 4096 单元，领域字段还有更小上限。");yield new JsonPrimitive(text);}
            case NUMBER -> {String text=reader.nextString();if(text.length()>32) throw new PartsImportException(Code.LIMIT,pointer,"数字令牌过长。","使用该字段支持的小整数。");try {yield new JsonPrimitive(new java.math.BigDecimal(text));}catch(NumberFormatException error) {throw new PartsImportException(Code.TYPE,pointer,"数字格式无效。","使用有限十进制整数。");}}
            case NULL -> {reader.nextNull();yield JsonNull.INSTANCE;}
            case END_DOCUMENT -> throw new PartsImportException(Code.SYNTAX,pointer,"文件提前结束，缺少 JSON 内容。","检查根对象和文件结尾是否完整。");
            default -> throw new PartsImportException(Code.TYPE,pointer,"这里不支持布尔值或其他 JSON 令牌。","请核对模板中的字段类型。");
        };}catch(PartsImportException error) {throw error;}catch(IOException error) {throw syntax(pointer,error);}
    }
}
