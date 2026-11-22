package com.f1car.persistence;

import java.io.IOException;

/** First-error diagnostic. Pointer uses RFC 6901; it is never a filesystem path. */
public final class PartsImportException extends IOException {
    public enum Code { SYNTAX,ENCODING,STRUCTURE,TYPE,RANGE,IDENTITY,DUPLICATE,LIMIT }
    private final Code code;
    private final String pointer,detail,hint;
    public PartsImportException(Code code,String pointer,String detail,String hint) {
        super((pointer.isEmpty()?"根对象":pointer)+"："+detail);this.code=code;this.pointer=pointer;this.detail=detail;this.hint=hint;
    }
    public Code code() {return code;}
    public String pointer() {return pointer;}
    public String detail() {return detail;}
    public String hint() {return hint;}
    public static String child(String parent,String token) {return parent+"/"+token.replace("~","~0").replace("/","~1");}
}
