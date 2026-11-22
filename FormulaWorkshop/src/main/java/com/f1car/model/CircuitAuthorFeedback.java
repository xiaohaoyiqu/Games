package com.f1car.model;

import java.util.*;

/** Explanations for existing geometry checks, not automatic repairs or driving/visibility scores. */
public final class CircuitAuthorFeedback {
    public record Advice(String title,String reason,List<String> actions,String verification) {public Advice {actions=List.copyOf(actions);}}
    private CircuitAuthorFeedback() {}
    public static Advice explain(CircuitInspector.Issue issue) {
        List<String> actions=switch(issue.code()) {
            case GEOMETRY->List.of("至少保留四个路线点，检查相邻点重复或过近。","逐次移动或删除相关点，保留起点和顺序意图。");
            case LENGTH->List.of("全长需在 400–2200 米之间；调整路线规模或简化绕行。","路宽或地图名称不能代替实际路线长度调整。");
            case BOUNDS->List.of("收回边缘路线点，并观察插值曲线的外扩。","点在工作区内，不保证两点之间的曲线也在范围内。");
            case CROSSING->List.of("定位交点，拉开相交的路线段。","当前是平面环线，不能把自交当成立交桥使用。");
            case CLEARANCE->List.of("对照两个标记，拉开相隔较远的路段。","检查道路宽度与缓冲区，别仅移动装饰掩盖贴近的路段。");
            case CURVATURE->List.of("放宽所标弯段相邻路线点，或分配更长的转向过渡。","一次只改一处，再观察是否把问题推到邻弯。");
            case START->List.of("起点前约 40 米、后约 20 米需要平顺的发车区域。","可调整附近点或明确换起点；换起点也会改变验证条件。");
            case OBJECT->List.of("先选中标记物件，把实际占地移出道路缓冲区。","长宽、朝向都会影响实体占地；当前检查要求距道路至少 2 米。");
            case REJOIN->List.of("检查起点侧面的汇入空间，移开护栏或建筑，拉开邻近道路。","先看几何结果，再通过实际试驾检查汇入体验。");
        };
        return new Advice(issue.code().title,issue.message(),actions,"这是几何修改建议；仍需四车验证。标牌可读性、视线与驾驶体验还需真人评审。");
    }
}
