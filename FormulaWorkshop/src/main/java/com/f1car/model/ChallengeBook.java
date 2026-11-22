package com.f1car.model;

import java.util.List;
import java.util.Objects;

/** Local, fixed-car activities; thresholds are versioned game rules pending driving calibration. */
public final class ChallengeBook {
    public enum Medal {
        NONE("未获得"),BRONZE("铜牌"),SILVER("银牌"),GOLD("金牌");
        public final String title;Medal(String title) { this.title=title; }
    }
    public enum Kind {
        CLEAN_LAPS("B01","净圈接力","1 个有效圈","连续 2 个有效圈","连续 3 圈，极差 ≤ max(0.75 秒, 均值×2.5%)"),
        CONSISTENCY("B02","稳定节奏","完成三圈稳定课程","极差 ≤ max(1 秒, 均值×3%)","极差 ≤ max(0.75 秒, 均值×2%)"),
        START("B03","精准起步","灯灭后合法完成加速区","反应时间 ≤ 0.45 秒","反应时间 ≤ 0.25 秒"),
        CHASE("B04","原厂追击","两圈干净获胜","完赛时领先 ≥ 15 米","完赛时领先 ≥ 30 米"),
        SMOOTH("B05","平顺试车","110 秒内完成有效整圈","洒水量 ≤ 40%","洒水量 ≤ 15%");
        public final String id,title,bronze,silver,gold;
        Kind(String id,String title,String bronze,String silver,String gold) { this.id=id;this.title=title;this.bronze=bronze;this.silver=silver;this.gold=gold; }
    }
    public record Entry(String trackId,Kind kind) {
        public Entry { TrackCatalog.require(trackId);Objects.requireNonNull(kind); }
        public TrackLayout track() { return TrackCatalog.require(trackId).layout(); }
        public String key(String physicsVersion) { return "book-v1:"+kind.id+":"+trackId+":"+track().geometryKey()+":"+physicsVersion+":"+Tuning.STOCK.performanceKey(); }
    }
    public static List<Entry> all() {
        return TrackCatalog.all().stream().flatMap(track->java.util.Arrays.stream(Kind.values()).map(kind->new Entry(track.id(),kind))).toList();
    }
    private ChallengeBook() {}
}
