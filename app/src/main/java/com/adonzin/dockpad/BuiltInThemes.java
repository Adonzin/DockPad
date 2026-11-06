package com.adonzin.dockpad;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Themes that come with DockPad, built from the same settings anyone can make. */
final class BuiltInThemes {
    private BuiltInThemes() {
    }

    static List<ThemeLibrary.Entry> all() {
        List<ThemeLibrary.Entry> out = new ArrayList<>();
        out.add(entry("builtin:default", base("Default", "The classic side panel in your system colours.")));

        MenuTheme glass = base("Glass", "A see-through centred panel with soft edges, a glow highlight and gentle sounds.");
        glass.layout = "grid";
        glass.position = "center";
        glass.width = 560;
        glass.columns = 4;
        glass.style.applyPalette("midnight");
        glass.style.palette = "custom";
        glass.style.panel1 = "#33FFFFFF";
        glass.style.panel2 = "#1AFFFFFF";
        glass.style.gradient = "linear";
        glass.style.angle = 270;
        glass.style.opacity = 100;
        glass.style.corners = 32;
        glass.style.border = 1;
        glass.style.borderColor = "#66FFFFFF";
        glass.style.highlight = "glow";
        glass.style.tile = "#1AFFFFFF";
        glass.style.dim = 45;
        glass.style.showClock = true;
        glass.fx.open = "pop";
        glass.fx.close = "fade";
        glass.fx.items = "stagger";
        glass.fx.pack = "glass";
        out.add(entry("builtin:glass", glass));

        MenuTheme retro = base("Retro terminal", "Green phosphor text, a monospace font, square corners and chiptune bleeps.");
        retro.layout = "compact";
        retro.position = "left";
        retro.width = 380;
        retro.style.applyPalette("retro");
        retro.style.corners = 0;
        retro.style.border = 2;
        retro.style.borderColor = "#39FF14";
        retro.style.font = "mono";
        retro.style.highlight = "outline";
        retro.style.showClock = true;
        retro.style.showBattery = true;
        retro.fx.open = "slide";
        retro.fx.close = "slide";
        retro.fx.speed = "fast";
        retro.fx.highlight = "flash";
        retro.fx.pack = "retro";
        out.add(entry("builtin:retro", retro));

        MenuTheme neon = base("Neon ring", "A glowing radial menu that spins in, aimed with the stick.");
        neon.layout = "radial";
        neon.size = 85;
        neon.style.applyPalette("neon");
        neon.style.glow = true;
        neon.style.highlight = "glow";
        neon.style.font = "condensed";
        neon.style.backdrop = "#0D0221";
        neon.style.dim = 70;
        neon.fx.open = "spin";
        neon.fx.close = "zoom";
        neon.fx.highlight = "pulse";
        neon.fx.pack = "glass";
        out.add(entry("builtin:neon", neon));

        MenuTheme console = base("Console", "Sections across the top and items below, like a console home screen.");
        console.layout = "xmb";
        console.size = 80;
        console.style.applyPalette("ocean");
        console.style.opacity = 85;
        console.style.highlight = "filled";
        console.style.showClock = true;
        console.style.showBattery = true;
        console.fx.open = "fade";
        console.fx.close = "fade";
        console.fx.highlight = "bump";
        console.fx.pack = "soft";
        out.add(entry("builtin:console", console));

        MenuTheme sunset = base("Sunset dock", "A small dock along the bottom in sunset colours, icons growing as you pass.");
        sunset.layout = "dock";
        sunset.band = "bottom";
        sunset.size = 70;
        sunset.style.applyPalette("sunset");
        sunset.style.corners = 28;
        sunset.style.highlight = "filled";
        sunset.style.dim = 25;
        sunset.fx.open = "slide";
        sunset.fx.close = "slide";
        sunset.fx.highlight = "hop";
        sunset.fx.pack = "soft";
        out.add(entry("builtin:sunset", sunset));
        return out;
    }

    private static MenuTheme base(String name, String description) {
        MenuTheme t = new MenuTheme();
        t.name = name;
        t.description = description;
        t.author = "DockPad";
        return t;
    }

    private static ThemeLibrary.Entry entry(String id, MenuTheme t) {
        ThemeLibrary.Entry e = new ThemeLibrary.Entry();
        e.id = id;
        e.name = t.name;
        e.author = t.author;
        e.description = t.description;
        e.builtIn = true;
        e.json = t.toJson();
        return e;
    }

    /** Checks the JSON keeps the description fields. */
    static String describe(String json) {
        try {
            return new JSONObject(json).optString("description", "");
        } catch (Exception e) {
            return "";
        }
    }
}
