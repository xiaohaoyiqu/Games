package com.f1car.model;

/** Keeps local profile, solo races and room names within the same display limits. */
public final class DriverName {
    public static final String DEFAULT="车手";
    private static final int LIMIT=20;

    private DriverName() {}

    public static String clean(String source) {
        if(source==null) return DEFAULT;
        var result=new StringBuilder(LIMIT);
        int accepted=0;
        for(int offset=0;offset<source.length() && accepted<LIMIT;) {
            int codePoint=source.codePointAt(offset);
            offset+=Character.charCount(codePoint);
            if(Character.isISOControl(codePoint) || codePoint>=0xd800 && codePoint<=0xdfff
                    || accepted==0 && Character.isWhitespace(codePoint)) continue;
            result.appendCodePoint(codePoint);
            accepted++;
        }
        String name=result.toString().strip();
        return name.isEmpty()?DEFAULT:name;
    }
}
