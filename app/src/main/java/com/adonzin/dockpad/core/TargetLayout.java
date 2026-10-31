package com.adonzin.dockpad.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.adonzin.dockpad.core.Codes.*;

/**
 * Decides which Android axes the built-in controls report, so translated input
 * lands on exactly the axes emulators were mapped to.
 */
public final class TargetLayout {
    /** For each logical axis (Mapper.LX..HY), the Android axes to write it to. */
    public final int[][] outputs = new int[Mapper.AXES][];
    public final int[] allAxes;

    public TargetLayout(Set<Integer> targetAxes) {
        boolean zrz = targetAxes.contains(AXIS_Z) && targetAxes.contains(AXIS_RZ);
        boolean rxry = targetAxes.contains(AXIS_RX) && targetAxes.contains(AXIS_RY);
        outputs[Mapper.LX] = new int[]{AXIS_X};
        outputs[Mapper.LY] = new int[]{AXIS_Y};
        if (zrz || !rxry) {
            outputs[Mapper.RX] = new int[]{AXIS_Z};
            outputs[Mapper.RY] = new int[]{AXIS_RZ};
        } else {
            outputs[Mapper.RX] = new int[]{AXIS_RX};
            outputs[Mapper.RY] = new int[]{AXIS_RY};
        }
        outputs[Mapper.LT] = pick(targetAxes, AXIS_LTRIGGER, AXIS_BRAKE);
        outputs[Mapper.RT] = pick(targetAxes, AXIS_RTRIGGER, AXIS_GAS);
        outputs[Mapper.HX] = new int[]{AXIS_HAT_X};
        outputs[Mapper.HY] = new int[]{AXIS_HAT_Y};

        List<Integer> all = new ArrayList<>();
        for (int[] o : outputs) for (int a : o) if (!all.contains(a)) all.add(a);
        allAxes = new int[all.size()];
        for (int i = 0; i < allAxes.length; i++) allAxes[i] = all.get(i);
    }

    private static int[] pick(Set<Integer> have, int a, int b) {
        boolean ha = have.contains(a), hb = have.contains(b);
        if (ha && hb) return new int[]{a, b};
        if (hb) return new int[]{b};
        return ha ? new int[]{a} : new int[]{a, b}; // unknown target: write both
    }
}
