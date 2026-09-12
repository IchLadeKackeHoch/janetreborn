# Lua scripting 🐈

Open **ClickGUI → Scripts → Open Script Folder** and add one `.lua` file per script. Files load and reload
automatically. Modules appear as normal headers in the **Scripts** tab. 💯

## Toggleable Module Example

```lua
local module = janet.module.create({
    id = "toggle_example",
    name = "Toggle Example",
    description = "A tiny toggle module"
})

function module:on_enable()
    janet.logger.info("Toggle Example enabled")
end

function module:on_disable()
    janet.logger.info("Toggle Example disabled")
end
```

## Minimalistic killaura example

```lua
local aura = janet.module.create({
    id = "minimal_killaura",
    name = "Minimal KillAura",
    description = "Attacks the nearest valid player"
})

local range = aura.settings:number({
    id = "range",
    name = "Range",
    default = 3.0,
    minimum = 1.0,
    maximum = 6.0,
    step = 0.1
})

function aura:on_tick()
    if not janet.combat.attack_ready() then return end
    local target = janet.combat.nearest_player(range:get())
    if target then janet.combat.attack(target.id) end
end
```

CombatSDK respects vanilla reach, cooldown, coordination, and friends.

Scripts **cannot** access java, files, processes, or raw packets. 👍🏻
