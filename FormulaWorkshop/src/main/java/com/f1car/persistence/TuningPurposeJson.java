package com.f1car.persistence;

import com.f1car.model.TuningPurpose;
import com.google.gson.*;

/** Keep legacy purpose exchange shape and digests byte-compatible. */
final class TuningPurposeJson {
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();
    private TuningPurposeJson() {}
    static JsonElement tree(TuningPurpose purpose) {
        var value=JSON.toJsonTree(purpose);
        if(purpose!=null && purpose.version()==1) for(String side:new String[]{"a","b"}) value.getAsJsonObject().getAsJsonObject(side).remove("fitted");
        return value;
    }
}
