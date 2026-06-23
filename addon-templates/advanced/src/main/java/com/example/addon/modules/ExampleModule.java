package com.example.addon.modules;

import com.example.addon.ExampleAddon;
import autismclient.modules.PackModule;
import autismclient.modules.PackModuleOption;
import autismclient.util.AutismClientMessaging;

// A toggleable module. With no category it auto-lands under a menu column named after your addon.
// Read option values with bool/integer/decimal/choice/text/list(id). Group options with .group("...").
public final class ExampleModule extends PackModule {
    public ExampleModule() {
        super(ExampleAddon.ID + ":example", "Example", "Demonstrates an AUTISM addon module.");

        option(PackModuleOption.bool("greet", "Greet on enable", true).group("General"));
        option(PackModuleOption.integer("amount", "Amount", 3, 1, 10, 1).group("General"));
        option(PackModuleOption.enumChoice("style", "Style", "Friendly", "Friendly", "Loud").group("Display"));
        option(PackModuleOption.bool("shout", "Shout in caps", false).group("Display"));
    }

    @Override
    public void onEnable() {
        if (bool("greet")) {
            String msg = "[Example] enabled - amount " + integer("amount") + ", style " + choice("style");
            if (bool("shout")) msg = msg.toUpperCase(java.util.Locale.ROOT);
            AutismClientMessaging.sendPrefixed("\u00a7b" + msg);
        }
    }
}
