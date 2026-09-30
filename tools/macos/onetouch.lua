-- Hammerspoon (https://www.hammerspoon.org): добавьте в ~/.hammerspoon/init.lua
-- ⌃⌥V — вставить из буфера OneTouch на рабочий стол.
local ONETOUCH = "/usr/local/bin/onetouch"
hs.hotkey.bind({"ctrl", "alt"}, "V", function()
  hs.task.new(ONETOUCH, function(code, out, err)
    hs.alert.show(code == 0 and ("OneTouch ✓ " .. out) or ("OneTouch: " .. err), 2)
  end, {"paste"}):start()
end)
