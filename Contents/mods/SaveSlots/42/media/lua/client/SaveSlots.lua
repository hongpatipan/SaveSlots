-- Save Slots: named manual saves ("Save As") for single-player.
-- Esc > SAVE AS... (or the hotkey) opens a window listing this world's saves
-- with a picture of the character, where you can save, overwrite, load,
-- rename and delete them without leaving the game.
-- File work is done in Java (SaveSlots.jar via ZombieBuddy, Lua table SaveSlotsJava).

require "ISUI/ISPanel"
require "ISUI/ISButton"
require "ISUI/ISLabel"
require "ISUI/ISModalDialog"
require "ISUI/ISScrollingListBox"
require "ISUI/ISTextBox"
require "ISUI/ISTextEntryBox"

SaveSlots = SaveSlots or {}

local options = PZAPI.ModOptions:create("SaveSlots", "Save Slots")
local keyOption = options:addKeyBind("saveAs", getText("UI_SaveSlots_KeySaveAs"), Keyboard.KEY_F6,
    getText("UI_SaveSlots_KeySaveAs_tooltip"))
local sleepOption = options:addTickBox("sleepSave", getText("UI_SaveSlots_SleepSaveOption"), true,
    getText("UI_SaveSlots_SleepSaveOption_tooltip"))

-- Minutes of play between auto backups for each choice of the option (0 = off).
local BACKUP_MINUTES = { 0, 10, 15, 30, 60 }
local backupOption = options:addComboBox("autoBackup", getText("UI_SaveSlots_AutoBackupOption"),
    getText("UI_SaveSlots_AutoBackupOption_tooltip"))
for i, minutes in ipairs(BACKUP_MINUTES) do
    local label = minutes == 0 and getText("UI_SaveSlots_Off") or getText("UI_SaveSlots_EveryMinutes", minutes)
    backupOption:addItem(label, minutes == 30)
end
local backupCountOption = options:addComboBox("autoBackupCount", getText("UI_SaveSlots_AutoBackupCount"),
    getText("UI_SaveSlots_AutoBackupCount_tooltip"))
for count = 1, 3 do
    backupCountOption:addItem(tostring(count), count == 2)
end
local backupNotifyOption = options:addTickBox("autoBackupNotify", getText("UI_SaveSlots_AutoBackupNotify"), true,
    getText("UI_SaveSlots_AutoBackupNotify_tooltip"))
local backupWarnOption = options:addTickBox("autoBackupWarn", getText("UI_SaveSlots_AutoBackupWarn"), true,
    getText("UI_SaveSlots_AutoBackupWarn_tooltip"))
-- Copies the world in the background while playing (experimental, off until it has proved itself).
local smoothOption = options:addTickBox("smoothSave", getText("UI_SaveSlots_SmoothOption"), false,
    getText("UI_SaveSlots_SmoothOption_tooltip"))
-- A tick box as well as the path: the game does not keep an emptied text box.
local folderOnOption = options:addTickBox("useSaveFolder", getText("UI_SaveSlots_UseFolderOption"), false,
    getText("UI_SaveSlots_UseFolderOption_tooltip"))
local folderOption = options:addTextEntry("saveFolder", getText("UI_SaveSlots_FolderOption"), "",
    getText("UI_SaveSlots_FolderOption_tooltip"))

-- The one slot that is overwritten each time the character falls asleep, and
-- the rotating auto backups ("Auto Backup 1", "Auto Backup 2", ...). Fixed
-- (not translated) so they stay the same slots whatever the language.
local SLEEP_SLOT = "Sleep Save"
local BACKUP_SLOT = "Auto Backup"

--- Slots the mod saves into by itself; the player can only load or delete them.
local function isAutoSlot(slot)
    return slot ~= nil and (slot.info.auto == "sleep" or slot.info.auto == "backup")
end

local FONT_HGT_SMALL = getTextManager():getFontHeight(UIFont.Small)
local FONT_HGT_MEDIUM = getTextManager():getFontHeight(UIFont.Medium)
local PAD = 12
local BUTTON_HGT = FONT_HGT_SMALL + 8
local THUMB = 64
local ROW_HGT = THUMB + 12

local function say(player, text, good)
    if not player then return end
    if good then
        HaloTextHelper.addGoodText(player, text)
    else
        HaloTextHelper.addBadText(player, text)
    end
end

----------------------------------------------------------------------------
-- Status bar at the bottom of the screen: the countdown before an Auto Backup,
-- "Saving...", "Saved" and errors. Replaces text above the character's head.
-- The game hides every UI element while the Esc menu is open, and shows it
-- again afterwards, so the bar hides itself whenever it has nothing to say.
----------------------------------------------------------------------------

SaveSlotsHud = ISPanel:derive("SaveSlotsHud")

local HUD_COLORS = {
    warn   = { 0.95, 0.75, 0.25 },
    saving = { 0.55, 0.75, 0.95 },
    done   = { 0.45, 0.85, 0.50 },
    error  = { 0.90, 0.35, 0.30 },
}
local HUD_BAR_HGT = 4

-- The game's own picture of Spiffo the builder (referenced, not copied), shown
-- beside the bar. It is a still picture, so it is moved in code: the hammer is
-- raised slowly and brought down fast, once every 0.7 seconds, while saving.
local MASCOT_FILE = "media/ui/spiffoBuilder.png"
local MASCOT_HGT = 44
local mascotTexture = nil

local function mascot()
    if mascotTexture == nil then
        mascotTexture = getTexture(MASCOT_FILE) or false
    end
    return mascotTexture or nil
end

function SaveSlotsHud:new()
    local o = ISPanel.new(self, 0, 0, 100, mascot() and (MASCOT_HGT + 12) or (FONT_HGT_SMALL + 20))
    o.backgroundColor = { r = 0, g = 0, b = 0, a = 0.72 }
    o.borderColor = { r = 0.5, g = 0.5, b = 0.5, a = 0.8 }
    o.moveWithMouse = false
    return o
end

--- Height of the picture at this moment: up to 6 pixels up, and a little flatter when it comes down.
local hudClock = nil

--- Where we are in a repeating cycle, 0 to under 1. The time is counted from when
--- the bar was first used: `getTimestampMs() % period` on the game's own clock gave
--- nonsense (the picture ended up billions of pixels off the screen).
local function phase(period)
    local now = getTimestampMs()
    if hudClock == nil then hudClock = now end
    local t = now - hudClock
    local p = (t - math.floor(t / period) * period) / period
    if p ~= p or p < 0 or p >= 1 then return 0 end
    return p
end

local function mascotBeat(kind)
    if kind == "done" then return 0, 1 end
    local p = phase(kind == "warn" and 1400 or 700)
    if p < 0.7 then
        return -6 * (p / 0.7), 1                     -- raising
    end
    local down = (p - 0.7) / 0.3                     -- coming down fast, then a squash on the hit
    return -6 * (1 - down), 1 - 0.08 * math.sin(down * math.pi)
end

--- The text cut to fit the width, with "..." when it had to be shortened.
local function fitText(text, maxWidth)
    local tm = getTextManager()
    if tm:MeasureStringX(UIFont.Small, text) <= maxWidth then return text end
    while #text > 1 and tm:MeasureStringX(UIFont.Small, text .. "...") > maxWidth do
        text = text:sub(1, #text - 1)
    end
    return text .. "..."
end

function SaveSlotsHud:prerender()
    if not self.kind then
        self:setVisible(false)
        return
    end
    if self.hideAt and getTimestampMs() >= self.hideAt then
        self.kind = nil
        self:setVisible(false)
        return
    end
    ISPanel.prerender(self)
    local c = HUD_COLORS[self.kind]
    local barY = self.height - HUD_BAR_HGT - 4
    -- Room for the picture on the left (not for errors).
    local left = PAD
    local tex = self.kind ~= "error" and mascot() or nil
    if tex then
        local h = MASCOT_HGT
        local w = math.floor(h * tex:getWidth() / math.max(1, tex:getHeight()))
        local lift, squash = mascotBeat(self.kind)
        -- Whole pixels: the picture was seen only while it stood still (fractions are suspect).
        local drawH = math.floor(h * squash + 0.5)
        local y = math.floor((self.height - h) / 2 + lift + (h - drawH) + 0.5)
        y = math.max(0, math.min(self.height - 1, y))   -- never off the bar
        self:drawTextureScaled(tex, PAD, y, w, drawH, 1, 1, 1, 1)
        left = PAD + w + PAD
    end
    local barW = self.width - left - PAD
    local percent = self.fraction and self.kind ~= "warn"
            and string.format("%d%%", math.floor(self.fraction * 100 + 0.5)) or nil
    local textW = barW - (percent and 50 or 0)
    local textY = math.floor((barY - FONT_HGT_SMALL) / 2)
    self:drawText(fitText(self.label or "", textW), left, textY, 1, 1, 1, 1, UIFont.Small)
    if percent then
        self:drawTextRight(percent, self.width - PAD, textY, 0.8, 0.8, 0.8, 1, UIFont.Small)
    end
    self:drawRect(left, barY, barW, HUD_BAR_HGT, 0.5, 0.2, 0.2, 0.2)
    if self.fraction then
        local w = math.max(0, math.min(1, self.fraction)) * barW
        self:drawRect(left, barY, w, HUD_BAR_HGT, 1, c[1], c[2], c[3])
    else
        -- No known progress: a block that slides along the bar.
        local blockW = barW * 0.25
        local t = phase(1200)
        local x0 = math.max(0, t * (barW + blockW) - blockW)
        local x1 = math.min(barW, t * (barW + blockW))
        if x1 > x0 then
            self:drawRect(left + x0, barY, x1 - x0, HUD_BAR_HGT, 1, c[1], c[2], c[3])
        end
    end
end

local hud = nil

--- Shows the bar. kind: "warn", "saving", "done" or "error".
--- fraction: 0 to 1, or nil when the progress is not known.
--- holdMs: hide itself after this long (nil: stays until replaced or hidden).
local function hudShow(kind, label, fraction, holdMs)
    if not hud then
        hud = SaveSlotsHud:new()
        hud:initialise()
        hud:instantiate()
        hud:addToUIManager()
        hud:setAlwaysOnTop(true)
        -- Clicks go through to the game below.
        hud.javaObject:setConsumeMouseEvents(false)
    end
    local sw, sh = getCore():getScreenWidth(), getCore():getScreenHeight()
    if hud.screenW ~= sw or hud.screenH ~= sh then
        hud.screenW, hud.screenH = sw, sh
        hud:setWidth(math.min(520, math.floor(sw * 0.6)))
        hud:setX(math.floor((sw - hud.width) / 2))
        hud:setY(sh - hud.height - 110)
    end
    hud.kind, hud.label, hud.fraction = kind, label, fraction
    hud.hideAt = holdMs and (getTimestampMs() + holdMs) or nil
    hud:setVisible(true)
end

--- Hides the bar, but only if it is showing this kind (not a newer message).
local function hudHide(kind)
    if hud and hud.kind and (kind == nil or hud.kind == kind) then
        hud.kind = nil
        hud:setVisible(false)
    end
end

local function javaReady()
    return SaveSlotsJava ~= nil and SaveSlotsJava.saveSlot ~= nil and SaveSlotsJava.listSlots ~= nil
end

-- The save folder last handed to Java, so it is only checked again when the
-- option changes (or the window opens, in case a drive was plugged in).
local appliedFolder = nil
local folderWarned = false

--- Tells Java where new saves go: the folder in the options, or "" for the game's Saves folder.
local function applySaveFolder(force)
    if not SaveSlotsJava.setSaveFolder then return end
    local text = tostring(folderOption:getValue() or "")
    -- Saves already in the folder stay usable when it is unticked.
    if SaveSlotsJava.setLookFolder then SaveSlotsJava.setLookFolder(text) end
    local wanted = folderOnOption:getValue() and text or ""
    if wanted == appliedFolder and not force then return end
    if wanted ~= appliedFolder then folderWarned = false end
    appliedFolder = wanted
    SaveSlotsJava.setSaveFolder(wanted)
end

--- Where saves go, as text for the window, and true when the chosen folder cannot be used.
local function saveFolderText()
    local status = SaveSlotsJava.saveFolderStatus and SaveSlotsJava.saveFolderStatus() or ""
    local reason, folder = status:match("^ERR:([^:]+):(.*)$")
    if reason then
        return getText("UI_SaveSlots_FolderProblem", folder, getText("UI_SaveSlots_FolderWhy_" .. reason)), true
    elseif status:sub(1, 3) == "OK:" then
        return getText("UI_SaveSlots_FolderChosen", status:sub(4)), false
    end
    return getText("UI_SaveSlots_FolderDefault"), false
end

local function defaultSlotName()
    local gt = getGameTime()
    return getText("UI_SaveSlots_DefaultName", gt:getDaysSurvived() + 1,
        string.format("%02d-%02d", gt:getHour(), gt:getMinutes()))
end

local function formatSize(bytes)
    if not bytes or bytes < 0 then return nil end
    if bytes >= 1024 * 1024 * 1024 then
        return string.format("%.1f GB", bytes / (1024 * 1024 * 1024))
    end
    return string.format("%d MB", math.floor(bytes / (1024 * 1024) + 0.5))
end

local function errorText(result)
    local reason, detail = result:match("^ERR:([^:]+):?(.*)$")
    if reason == "exists" then
        return getText("UI_SaveSlots_ErrExists", detail)
    elseif reason == "foreign" then
        return getText("UI_SaveSlots_ErrForeign", detail)
    elseif reason == "empty_name" then
        return getText("UI_SaveSlots_ErrEmptyName")
    elseif reason == "multiplayer" then
        return getText("UI_SaveSlots_ErrMultiplayer")
    elseif reason == "nosave" then
        return getText("UI_SaveSlots_ErrNoSave")
    elseif reason == "chunks_busy" then
        return getText("UI_SaveSlots_ErrBusy")
    elseif reason == "playing" then
        return getText("UI_SaveSlots_ErrPlaying")
    elseif reason == "not_found" then
        return getText("UI_SaveSlots_ErrNotFound", detail)
    end
    return getText("UI_SaveSlots_ErrGeneric", tostring(detail ~= "" and detail or reason))
end

----------------------------------------------------------------------------
-- Slot details
----------------------------------------------------------------------------

--- Details of the character right now, stored with the slot as "key=value|...".
local function collectInfo()
    local player = getPlayer()
    local parts = {}
    local function add(key, fn)
        local ok, value = pcall(fn)
        if ok and value ~= nil and value ~= "" then
            local clean = string.gsub(tostring(value), "[|=\r\n\t]", " ")
            table.insert(parts, key .. "=" .. clean)
        end
    end
    add("char", function()
        local desc = player:getDescriptor()
        return desc:getForename() .. " " .. desc:getSurname()
    end)
    add("prof", function()
        local profession = player:getDescriptor():getCharacterProfession()
        local def = CharacterProfessionDefinition.getCharacterProfessionDefinition(profession)
        return def:getUIName()
    end)
    -- The id too, so the name can be shown in the language of the moment.
    add("profid", function() return tostring(player:getDescriptor():getCharacterProfession()) end)
    add("days", function() return math.floor(player:getHoursSurvived() / 24) + 1 end)
    add("kills", function() return player:getZombieKills() end)
    add("infected", function() return player:getBodyDamage():IsInfected() and 1 or 0 end)
    return table.concat(parts, "|")
end

local function parseInfo(text)
    local info = {}
    for part in string.gmatch(text or "", "[^|]+") do
        local key, value = part:match("^([^=]+)=(.*)$")
        if key then info[key] = value end
    end
    return info
end

--- Profession name in the current language, or the name stored with the slot.
local function professionName(info)
    if info.profid then
        local ok, name = pcall(function()
            local defs = CharacterProfessionDefinition.getProfessions()
            for i = 0, defs:size() - 1 do
                local def = defs:get(i)
                if tostring(def:getType()) == info.profid then return def:getUIName() end
            end
        end)
        if ok and name then return name end
    end
    return info.prof
end

--- The main world and this world's slots (see SaveSlotsApi.listSlots).
local function readSlots()
    applySaveFolder()
    local slots = {}
    for line in string.gmatch(SaveSlotsJava.listSlots() or "", "[^\n]+") do
        local f = {}
        for field in string.gmatch(line .. "\t", "([^\t]*)\t") do
            table.insert(f, field)
        end
        if #f >= 8 then
            table.insert(slots, {
                name = f[1],
                millis = tonumber(f[2]) or 0,
                current = f[3] == "1",
                when = f[4],
                rel = f[5],
                size = tonumber(f[6]),
                main = f[7] == "1",
                info = parseInfo(f[8]),
            })
        end
    end
    return slots
end

--- Character name and state from the save itself, for slots saved without details.
local function fillFromSaveInfo(slot)
    if slot.saveInfoChecked then return end
    slot.saveInfoChecked = true
    -- The game only reads saves in its Saves folder; these have their details stored.
    if slot.rel:sub(1, 1) == "@" then return end
    local ok, saveInfo = pcall(getSaveInfo, slot.rel)
    if not ok or not saveInfo then return end
    local players = saveInfo.players
    if players and players[1] and not slot.info.char then
        slot.info.char = players[1].name
    end
    if saveInfo.playerAlive == false then
        slot.dead = true
    end
end

----------------------------------------------------------------------------
-- Actions
----------------------------------------------------------------------------

-- The game writes the save's picture (thumb.png) on the render thread a frame
-- or more after saveGame(). Copying before that would give the slot the
-- picture of the previous save, so wait for it (at most about 3 seconds).
local THUMB_WAIT_TICKS = 180
-- Before saving, the status bar is shown for at least this many frames and
-- milliseconds, so it is on screen while the game is stopped.
local BAR_FRAMES = 3
local BAR_MS = 120
local saving = false

-- Counts game sessions. A save still waiting for its picture when the player
-- quits to the main menu is dropped, not run later in whatever game comes next.
local gameSession = 0
Events.OnGameStart.Add(function() gameSession = gameSession + 1 end)

-- OnTick stops while the game is paused; OnTickEvenPaused keeps running.
local tickEvent = Events.OnTickEvenPaused or Events.OnTick

--- Pauses the game and returns the speed to go back to (nil if it was not changed).
local function pauseGame()
    local controls = UIManager.getSpeedControls()
    if not controls or isClient() then return nil end
    local previous = controls:getCurrentGameSpeed()
    setShowPausedMessage(false)
    controls:SetCurrentGameSpeed(0)
    return previous
end

--- Undoes pauseGame, unless the player changed the speed in the meantime.
local function resumeGame(previous)
    if previous == nil then return end
    setShowPausedMessage(true)
    local controls = UIManager.getSpeedControls()
    if controls and controls:getCurrentGameSpeed() == 0 and previous > 0 then
        controls:SetCurrentGameSpeed(previous)
    end
end

--- Saves the game, then copies it into the slot once the new picture is written.
--- resumeSpeed: the speed to restore when done (the game stays paused until then).
--- quiet: no message when it worked (errors are always shown).
---
--- Two ways to copy. Plain: the game stops while the whole world is copied.
--- Smooth (option, experimental): the world is copied in the background while
--- the game runs and the bar shows the progress; then the game stops for a
--- moment only to copy what changed meanwhile (see SaveSlotsApi).
function SaveSlots.save(name, overwrite, extraInfo, resumeSpeed, quiet)
    if saving then
        resumeGame(resumeSpeed)
        return
    end
    saving = true
    applySaveFolder()
    local player = getPlayer()
    local session = gameSession
    local smooth = smoothOption:getValue() and SaveSlotsJava.beginPrecopy ~= nil
    local info

    -- For the log: how long the game stood still while saving.
    local startedAt, saveGameMs = getTimestampMs(), 0
    local gapLast, gapMax, gapSlow = nil, 0, 0
    local function meterTick()
        local now = getTimestampMs()
        if gapLast then
            local gap = now - gapLast
            if gap > gapMax then gapMax = gap end
            if gap > 50 then gapSlow = gapSlow + 1 end
        end
        gapLast = now
    end
    local function stopMeter()
        tickEvent.Remove(meterTick)
    end

    --- The player quit (or a new game started) while waiting: nothing to copy.
    local function gone()
        return gameSession ~= session or getPlayer() ~= player
    end
    local function abandon()
        stopMeter()
        saving = false
        hudHide()
        if SaveSlotsJava.cancelPrecopy then pcall(SaveSlotsJava.cancelPrecopy) end
    end

    --- The slot is made, or it failed: say how it went.
    local function report(ok, result)
        stopMeter()
        print("[SaveSlots] while saving: longest wait between frames " .. math.floor(gapMax) .. " ms, "
                .. gapSlow .. " waits over 50 ms, saveGame() took " .. math.floor(saveGameMs) .. " ms, all of it "
                .. math.floor(getTimestampMs() - startedAt) .. " ms")
        saving = false
        resumeGame(resumeSpeed)
        if ok and result and result:sub(1, 3) == "OK:" then
            if quiet then
                hudHide("saving")
            else
                hudShow("done", getText("UI_SaveSlots_Saved", result:sub(4)), 1, 2500)
            end
            -- Saved in the game's Saves folder instead: say why, once.
            local text, bad = saveFolderText()
            if bad and not folderWarned then
                folderWarned = true
                hudShow("error", text, nil, 7000)
            end
        else
            hudShow("error", errorText(ok and result or "ERR:unknown"), nil, 6000)
        end
    end

    --- Saves the game, waits for its picture (written by the render thread a
    --- frame or more later), then runs step on a following tick.
    local function saveThenWait(step)
        local stampBefore = SaveSlotsJava.thumbStamp and SaveSlotsJava.thumbStamp() or nil
        local saveStart = getTimestampMs()
        saveGame()
        saveGameMs = saveGameMs + (getTimestampMs() - saveStart)
        local ticks = 0
        local function waitForThumb()
            if gone() then
                tickEvent.Remove(waitForThumb)
                abandon()
                return
            end
            ticks = ticks + 1
            if stampBefore ~= nil and ticks < THUMB_WAIT_TICKS and SaveSlotsJava.thumbStamp() == stampBefore then
                return
            end
            tickEvent.Remove(waitForThumb)
            -- One more tick so the render thread is surely done with the file.
            local function nextTick()
                tickEvent.Remove(nextTick)
                step()
            end
            tickEvent.Add(nextTick)
        end
        tickEvent.Add(waitForThumb)
    end

    --- Plain: the whole world is copied now, with the game stopped.
    local function copyAtOnce()
        if gone() then
            abandon()
            return
        end
        local ok, result = pcall(SaveSlotsJava.saveSlot, name, overwrite == true, info)
        report(ok, result)
    end

    --- Smooth, last step: copy what changed while the world was being copied.
    local function finishCopy()
        if gone() then
            abandon()
            return
        end
        local ok, result = pcall(SaveSlotsJava.finishPrecopy)
        report(ok, result)
    end

    --- Smooth: follow the copy in the background, then finish.
    local function watchCopy()
        local function poll()
            if gone() then
                tickEvent.Remove(poll)
                abandon()
                return
            end
            local status = SaveSlotsJava.precopyStatus() or ""
            local done, total = status:match("^RUNNING:(%d+):(%d+)$")
            if done then
                if not quiet then
                    local all = tonumber(total) or 0
                    local fraction = all > 0 and math.min(0.99, tonumber(done) / all) or nil
                    hudShow("saving", getText("UI_SaveSlots_Saving", name), fraction)
                end
                return
            end
            tickEvent.Remove(poll)
            if status == "READY" then
                -- No second saveGame(): it makes the game write most of the world again,
                -- which would all have to be copied again. The copy is brought up to
                -- date with what the game wrote meanwhile, which is the state the disk
                -- would be in if the game stopped right now.
                finishCopy()
            else
                -- It could not be copied in the background: do it the plain way.
                print("[SaveSlots] Copy in the background did not work (" .. status .. "), copying at once")
                copyAtOnce()
            end
        end
        tickEvent.Add(poll)
    end

    local function startCopy()
        if gone() then
            abandon()
            return
        end
        local ok, started = pcall(SaveSlotsJava.beginPrecopy, name, overwrite == true, info)
        if not ok or started == nil then
            copyAtOnce()
        elseif started:sub(1, 3) == "OK:" then
            report(true, started)   -- the slot being played was saved in place
        elseif started == "STARTED" then
            watchCopy()
        else
            copyAtOnce()            -- the plain way says what is wrong
        end
    end

    --- The game stops while it saves, so this runs after the bar has been drawn.
    local function begin()
        tickEvent.Add(meterTick)
        info = collectInfo()
        if extraInfo then info = info .. "|" .. extraInfo end
        saveThenWait(smooth and startCopy or copyAtOnce)
    end

    if quiet then
        begin()
        return
    end
    hudShow("saving", getText("UI_SaveSlots_Saving", name), nil)
    local shownAt, frames = getTimestampMs(), 0
    local function waitForBar()
        if gone() then
            tickEvent.Remove(waitForBar)
            abandon()
            return
        end
        frames = frames + 1
        if frames < BAR_FRAMES or getTimestampMs() - shownAt < BAR_MS then return end
        tickEvent.Remove(waitForBar)
        begin()
    end
    tickEvent.Add(waitForBar)
end

--- Saves and quits to the main menu like the Esc menu's QUIT, then loads the save.
function SaveSlots.load(rel)
    if isQuitCooldown() then return end
    SaveSlotsJava.requestLoad(rel)
    setGameSpeed(1)
    pauseSoundAndMusic()
    setShowPausedMessage(true)
    getCore():quit()
end

-- Back at the main menu: load the save picked in game, if any. Checked once
-- each time the main menu is entered.
local pendingArmed = true
local pendingTicks = 0
Events.OnMainMenuEnter.Add(function()
    pendingArmed = true
    pendingTicks = 0
end)
local function onFETick()
    if not pendingArmed then return end
    if not (MainScreen and MainScreen.instance and not MainScreen.instance.inGame) then return end
    -- Let the main menu finish building first.
    pendingTicks = pendingTicks + 1
    if pendingTicks < 15 then return end
    pendingArmed = false
    if not javaReady() then return end
    local rel = SaveSlotsJava.takePendingLoad()
    if rel == nil or rel == "" then return end
    applySaveFolder()
    -- Copy a slot into the main world first: the game can only open saves
    -- in its Saves folder, and slots may be kept in the chosen folder.
    if SaveSlotsJava.slotOf(rel) ~= "" then
        local restored = SaveSlotsJava.restoreSlot(rel)
        if not restored or restored:sub(1, 3) ~= "OK:" then
            print("[SaveSlots] Could not load " .. tostring(rel) .. ", nothing changed: " .. tostring(restored))
            return
        end
        rel = restored:sub(4)
    end
    local ok, saveInfo = pcall(getSaveInfo, rel)
    if not ok or not saveInfo or not saveInfo.saveName then
        print("[SaveSlots] Could not read save to load: " .. tostring(rel))
        return
    end
    print("[SaveSlots] Loading " .. tostring(rel))
    -- Already confirmed in game.
    SaveSlots.continueSave(saveInfo.gameMode, saveInfo.saveName)
end
Events.OnFETick.Add(onFETick)

-- Every way of opening a save (Load Game, Continue, the Load button) goes
-- through MainScreen.continueLatestSave. A slot is never opened itself: it is
-- copied into the main world first and played there, so the game never saves
-- into a slot and slots keep what they were saved with.
require "OptionScreens/MainScreen"
local originalContinue = MainScreen.continueLatestSave

--- Copies the slot into the main world (if it is a slot) and opens the main world.
function SaveSlots.continueSave(gameMode, saveName)
    if javaReady() and SaveSlotsJava.restoreSlot and gameMode and saveName then
        local rel = gameMode .. "/" .. saveName
        if SaveSlotsJava.slotOf(rel) ~= "" then
            local restored = SaveSlotsJava.restoreSlot(rel)
            if not restored or restored:sub(1, 3) ~= "OK:" then
                print("[SaveSlots] Could not load " .. rel .. ", nothing changed: " .. tostring(restored))
                return
            end
            saveName = restored:sub(4):match("([^\\/]+)$")
        end
    end
    return originalContinue(gameMode, saveName)
end

--- Asks before a slot replaces the main world from the game's own menus.
local function confirmMenuLoad(slot, onYes)
    local text = getText("UI_SaveSlots_ConfirmLoad", slot)
    local modal = ISModalDialog:new(0, 0, 300, 120, text, true, nil, function(_, button)
        MainScreen.instance.checkSavefileModal = nil
        if button.internal == "YES" then
            onYes()
        else
            MainScreen.instance.bottomPanel:setVisible(true)
        end
    end)
    modal:initialise()
    modal:setX((getCore():getScreenWidth() - modal:getWidth()) / 2)
    modal:setY((getCore():getScreenHeight() - modal:getHeight()) / 2)
    modal:addToUIManager()
    modal:setAlwaysOnTop(true)
    MainScreen.instance.checkSavefileModal = modal
end

MainScreen.continueLatestSave = function(gameMode, saveName)
    local slot = ""
    if javaReady() and SaveSlotsJava.slotOf and gameMode and saveName then
        slot = SaveSlotsJava.slotOf(gameMode .. "/" .. saveName)
    end
    if slot == "" or not MainScreen.instance then
        return SaveSlots.continueSave(gameMode, saveName)
    end
    -- Opened from the game's own menus: replacing the main world needs a yes.
    confirmMenuLoad(slot, function() SaveSlots.continueSave(gameMode, saveName) end)
end

----------------------------------------------------------------------------
-- Load Game: the game only lists saves in its Saves folder. Saves kept in
-- the chosen folder get a link there (SaveSlotsApi.syncLinks), so they show
-- and work like any save: details, picture, mods, Delete and More.
----------------------------------------------------------------------------

require "OptionScreens/LoadGameScreen"

local function syncLinks()
    if not (javaReady() and SaveSlotsJava.syncLinks) then return end
    applySaveFolder(true)
    local ok, result = pcall(SaveSlotsJava.syncLinks)
    if not ok or (result and result:sub(1, 3) ~= "OK:") then
        print("[SaveSlots] Linking saves for Load Game failed: " .. tostring(result))
    end
end

local originalSetSaveGamesList = LoadGameScreen.setSaveGamesList
function LoadGameScreen:setSaveGamesList()
    syncLinks()
    return originalSetSaveGamesList(self)
end

Events.OnMainMenuEnter.Add(syncLinks)

----------------------------------------------------------------------------
-- Sleep Save: falling asleep overwrites the "Sleep Save" slot.
----------------------------------------------------------------------------

--- Saves into the Sleep Save slot, unless the player made a slot with that name.
function SaveSlots.sleepSave()
    if not (javaReady() and SaveSlotsJava.isAvailable()) then return end
    local existing
    for _, slot in ipairs(readSlots()) do
        if not slot.main and string.lower(slot.name) == string.lower(SLEEP_SLOT) then
            existing = slot
        end
    end
    if existing and existing.info.auto ~= "sleep" then
        say(getPlayer(), getText("UI_SaveSlots_ErrSleepTaken", SLEEP_SLOT), false)
        return
    end
    SaveSlots.save(SLEEP_SLOT, existing ~= nil, "auto=sleep")
end

-- Nil until the first update, so loading a save where the character already
-- sleeps does not count as falling asleep.
local wasAsleep = nil
Events.OnGameStart.Add(function() wasAsleep = nil end)
local function onPlayerUpdate(player)
    if player:getPlayerNum() ~= 0 then return end
    local asleep = player:isAsleep()
    local fellAsleep = wasAsleep == false and asleep
    wasAsleep = asleep
    if not fellAsleep or not sleepOption:getValue() or player:isDead() then return end
    -- Outside the player update, like the Esc menu item.
    local function once()
        Events.OnTick.Remove(once)
        local ok, err = pcall(SaveSlots.sleepSave)
        if not ok then print("[SaveSlots] Sleep Save failed: " .. tostring(err)) end
    end
    Events.OnTick.Add(once)
end
Events.OnPlayerUpdate.Add(onPlayerUpdate)

----------------------------------------------------------------------------
-- Auto Backup: every N minutes of play, the oldest "Auto Backup <n>" slot is
-- replaced, so a crash or power cut never leaves you without a recent copy.
----------------------------------------------------------------------------

local function backupIntervalMs()
    local minutes = BACKUP_MINUTES[backupOption:getValue()] or 0
    return minutes * 60000
end

--- Saves into the free or oldest Auto Backup slot. Slots the player named
--- "Auto Backup <n>" themselves are left alone.
function SaveSlots.autoBackup()
    if not (javaReady() and SaveSlotsJava.isAvailable()) then return end
    local byName = {}
    for _, slot in ipairs(readSlots()) do
        if not slot.main then byName[string.lower(slot.name)] = slot end
    end
    local target, targetSlot, taken
    for n = 1, backupCountOption:getValue() do
        local name = BACKUP_SLOT .. " " .. n
        local slot = byName[string.lower(name)]
        if slot == nil then
            target, targetSlot = name, nil
            break
        elseif slot.info.auto == "backup" and not slot.current then
            if target == nil or slot.millis < targetSlot.millis then
                target, targetSlot = name, slot
            end
        else
            taken = name
        end
    end
    if target == nil then
        if taken then say(getPlayer(), getText("UI_SaveSlots_ErrAutoTaken", taken), false) end
        return
    end
    SaveSlots.save(target, targetSlot ~= nil, "auto=backup", nil, not backupNotifyOption:getValue())
end

-- Play time since the last backup. Only counted while the game runs (OnTick
-- stops while paused), and a long gap between ticks (alt-tab, loading) is not.
local backupElapsedMs = 0
local lastTickMs = nil
local warnShown = false
Events.OnGameStart.Add(function()
    backupElapsedMs = 0
    lastTickMs = nil
    warnShown = false
end)

-- An Auto Backup is taken while the game is paused (Esc menu or the pause
-- key), when nobody notices the short stop: from a minute before it is due.
-- If the player does not pause within a few minutes after it is due (see
-- waitMs), it is taken while playing, after a 30 and 10 second warning and
-- once no zombie is near.
local EARLY_MS = 60000
local WAIT_MAX_MS = 3 * 60000

--- How long past the due time to wait for a quiet moment: up to 3 minutes, but
--- never more than a fifth of the interval (a 10 minute interval waits 2).
local function waitMs(interval)
    return math.min(WAIT_MAX_MS, math.floor(interval / 5))
end
local WARN_MS = 30000
-- Zombies this close (tiles, same floor) hold a backup while playing, unless
-- it is this long overdue (a zombie stuck outside the base must not stop it).
local ZOMBIE_RADIUS = 12
local OVERDUE_MS = 5 * 60000
local SAFE_CHECK_MS = 1000

local function zombieNear(player, radius)
    local cell = getCell()
    local list = cell and cell:getZombieList()
    if not list then return false end
    local px, py, pz = player:getX(), player:getY(), player:getZ()
    local r2 = radius * radius
    for i = 0, list:size() - 1 do
        local zombie = list:get(i)
        if zombie and not zombie:isDead() and math.abs(zombie:getZ() - pz) < 1 then
            local dx, dy = zombie:getX() - px, zombie:getY() - py
            if dx * dx + dy * dy <= r2 then return true end
        end
    end
    return false
end

--- Not in the middle of something: no zombies on you or nearby, not asleep, not driving fast.
local function safeToBackup(player, overdue)
    if not player or player:isDead() or player:isAsleep() then return false end
    if SaveSlots.dialog then return false end
    local stats = player:getStats()
    if stats:getNumChasingZombies() > 0 or stats:getNumVeryCloseZombies() > 0 then return false end
    if not overdue and zombieNear(player, ZOMBIE_RADIUS) then return false end
    local vehicle = player:getVehicle()
    if vehicle and math.abs(vehicle:getCurrentSpeedKmHour()) > 5 then return false end
    return true
end

--- Countdown bar for the last 30 seconds before an Auto Backup taken while playing.
local function warnBackup(player, msLeft)
    local on = player and backupWarnOption:getValue()
    if on and msLeft <= WARN_MS and msLeft > 0 then
        warnShown = true
        hudShow("warn", getText("UI_SaveSlots_BackupSoon", math.ceil(msLeft / 1000)), msLeft / WARN_MS)
    elseif warnShown then
        warnShown = false
        hudHide("warn")
    end
end

local function runBackup()
    backupElapsedMs = 0
    warnShown = false
    hudHide("warn")
    local ok, err = pcall(SaveSlots.autoBackup)
    if not ok then print("[SaveSlots] Auto Backup failed: " .. tostring(err)) end
end

-- While playing: count play time, and take an overdue backup at a safe moment.
local lastSafeCheckMs = 0
local function onBackupTick()
    local now = getTimestampMs()
    if lastTickMs then
        local gap = now - lastTickMs
        if gap > 0 and gap < 2000 then backupElapsedMs = backupElapsedMs + gap end
    end
    lastTickMs = now
    local interval = backupIntervalMs()
    if interval <= 0 or saving then return end
    local player = getPlayer()
    local deadline = interval + waitMs(interval)
    if backupElapsedMs < deadline then
        warnBackup(player, deadline - backupElapsedMs)
        return
    end
    -- Due: the countdown is over. Not now if it is not safe; check again every
    -- second, so it happens as soon as it is.
    if warnShown then
        warnShown = false
        hudHide("warn")
    end
    if now - lastSafeCheckMs < SAFE_CHECK_MS then return end
    lastSafeCheckMs = now
    if safeToBackup(player, backupElapsedMs - deadline > OVERDUE_MS) then runBackup() end
end
Events.OnTick.Add(onBackupTick)

-- While paused: the best moment, nobody notices the stop.
local function onPausedBackupTick()
    if saving or not isGamePaused() or SaveSlots.dialog then return end
    local interval = backupIntervalMs()
    if interval <= 0 or backupElapsedMs < interval - EARLY_MS then return end
    local player = getPlayer()
    if not player or player:isDead() then return end
    runBackup()
end
if Events.OnTickEvenPaused then Events.OnTickEvenPaused.Add(onPausedBackupTick) end

--- Minutes of play left until the next backup, or nil when Auto Backup is off.
local function minutesToNextBackup()
    local interval = backupIntervalMs()
    if interval <= 0 then return nil end
    -- Second value: it is due and waiting for a quiet moment (not "0 min").
    return math.max(0, math.ceil((interval - backupElapsedMs) / 60000)), backupElapsedMs >= interval
end

----------------------------------------------------------------------------
-- Window
----------------------------------------------------------------------------

SaveSlotsDialog = ISPanel:derive("SaveSlotsDialog")

function SaveSlotsDialog:new(x, y, width, height, player)
    local o = ISPanel.new(self, x, y, width, height)
    o.player = player
    o.backgroundColor = { r = 0, g = 0, b = 0, a = 0.92 }
    o.borderColor = { r = 0.4, g = 0.4, b = 0.4, a = 1 }
    o.moveWithMouse = true
    return o
end

local function addButton(self, x, y, width, title, onclick)
    local button = ISButton:new(x, y, width, BUTTON_HGT, title, self, onclick)
    button:initialise()
    button:instantiate()
    self:addChild(button)
    return button
end

function SaveSlotsDialog:createChildren()
    ISPanel.createChildren(self)
    local y = PAD

    self.titleLabel = ISLabel:new(PAD, y, FONT_HGT_MEDIUM, getText("UI_SaveSlots_DialogTitle"), 1, 1, 1, 1, UIFont.Medium, true)
    self.titleLabel:initialise()
    self:addChild(self.titleLabel)
    y = y + FONT_HGT_MEDIUM + PAD / 2

    self.listLabel = ISLabel:new(PAD, y, FONT_HGT_SMALL, getText("UI_SaveSlots_ListLabel"), 0.8, 0.8, 0.8, 1, UIFont.Small, true)
    self.listLabel:initialise()
    self:addChild(self.listLabel)
    y = y + FONT_HGT_SMALL + 4

    local bottomArea = FONT_HGT_SMALL + 4 + BUTTON_HGT + PAD + BUTTON_HGT + PAD + (FONT_HGT_SMALL + 4) * 2 + PAD
    local listHeight = self.height - y - bottomArea - PAD
    self.list = ISScrollingListBox:new(PAD, y, self.width - PAD * 2, listHeight)
    self.list:initialise()
    self.list:instantiate()
    self.list.font = UIFont.Small
    self.list.fontHgt = FONT_HGT_SMALL
    self.list.itemheight = ROW_HGT
    self.list.drawBorder = true
    self.list:setOnMouseDownFunction(self, SaveSlotsDialog.onSelectSlot)
    self.list.doDrawItem = SaveSlotsDialog.drawSlotItem
    self.list.dialog = self
    self:addChild(self.list)
    y = y + listHeight + PAD

    self.nameLabel = ISLabel:new(PAD, y, FONT_HGT_SMALL, getText("UI_SaveSlots_NameLabel"), 0.8, 0.8, 0.8, 1, UIFont.Small, true)
    self.nameLabel:initialise()
    self:addChild(self.nameLabel)
    y = y + FONT_HGT_SMALL + 4

    local saveWidth = 120
    self.entry = ISTextEntryBox:new(defaultSlotName(), PAD, y, self.width - PAD * 3 - saveWidth, BUTTON_HGT)
    self.entry:initialise()
    self.entry:instantiate()
    self.entry:setMaxTextLength(48)
    self.entry.onCommandEntered = function() self:onSave() end
    self:addChild(self.entry)
    self.saveButton = addButton(self, self.width - PAD - saveWidth, y, saveWidth,
        getText("UI_SaveSlots_SaveButton"), SaveSlotsDialog.onSave)
    y = y + BUTTON_HGT + PAD

    local w = 120
    self.loadButton = addButton(self, PAD, y, w, getText("UI_SaveSlots_LoadButton"), SaveSlotsDialog.onLoad)
    self.renameButton = addButton(self, PAD * 2 + w, y, w, getText("UI_SaveSlots_RenameButton"), SaveSlotsDialog.onRename)
    self.deleteButton = addButton(self, PAD * 3 + w * 2, y, w, getText("UI_SaveSlots_DeleteButton"), SaveSlotsDialog.onDelete)
    self.closeButton = addButton(self, self.width - PAD - w, y, w, getText("UI_SaveSlots_CloseButton"), SaveSlotsDialog.onCancel)
    y = y + BUTTON_HGT + PAD

    self.footerY = y

    self:refreshList()
end

--- Main world first, then the automatic saves (newest first), then the player's own.
--- Pictures are only reloaded from disk when they changed (see SaveSlotsApi.thumbnail).
function SaveSlotsDialog:refreshList()
    local selectedName = self:selectedSlot() and self:selectedSlot().name
    self.list:clear()
    -- Slots in the save folder show their picture through their link in Saves.
    syncLinks()
    self.slots = readSlots()
    self.folderText, self.folderBad = saveFolderText()
    self.totalSize, self.slotCount, self.autoSize = 0, 0, 0
    local main, autos, manual = {}, {}, {}
    for _, slot in ipairs(self.slots) do
        fillFromSaveInfo(slot)
        local size = (slot.size and slot.size > 0) and slot.size or 0
        if slot.main then
            table.insert(main, slot)
        elseif isAutoSlot(slot) then
            table.insert(autos, slot)
            self.autoSize = self.autoSize + size
        else
            table.insert(manual, slot)
            self.slotCount = self.slotCount + 1
            self.totalSize = self.totalSize + size
        end
    end
    table.sort(autos, function(a, b) return a.millis > b.millis end)

    local function addSection(title, slots)
        if #slots == 0 then return end
        if title then self.list:addItem(title, { header = title }) end
        for _, slot in ipairs(slots) do self.list:addItem(slot.name, slot) end
    end
    addSection(nil, main)
    addSection(getText("UI_SaveSlots_SectionAuto"), autos)
    addSection(getText("UI_SaveSlots_SectionManual"), manual)

    self.list.selected = 0
    for i, item in ipairs(self.list.items) do
        if not item.item.header and item.item.name == selectedName then
            self.list.selected = i
        end
    end
    self.lastSelected = self.list.selected
    self:updateButtons()
end

function SaveSlotsDialog:selectedSlot()
    if not self.list then return nil end
    local item = self.list.items[self.list.selected]
    if not item or item.item.header then return nil end
    return item.item
end

function SaveSlotsDialog:updateButtons()
    local slot = self:selectedSlot()
    local manageable = slot ~= nil and not slot.main and not slot.current
    self.loadButton:setEnable(slot ~= nil and not slot.current)
    -- Automatic saves are managed by the mod: load or delete only.
    self.renameButton:setEnable(manageable and not isAutoSlot(slot))
    self.deleteButton:setEnable(manageable)
end

local HEADER_HGT = FONT_HGT_SMALL + 10
local ICON_SIZE = 16
local slotIcons = {}

--- The game's own pictures: an alarm clock for Auto Backup, the tired moodle for Sleep Save.
local function slotIcon(kind)
    if slotIcons[kind] == nil then
        local name = kind == "sleep" and "Moodle_Icon_Tired" or "Item_AlarmClock"
        slotIcons[kind] = getTexture(name) or false
    end
    return slotIcons[kind] or nil
end

function SaveSlotsDialog.drawSlotItem(list, y, item, alt)
    local slot = item.item
    if slot.header then
        list:drawRect(0, y, list:getWidth(), HEADER_HGT, 0.6, 0.12, 0.12, 0.12)
        list:drawText(slot.header, 10, y + (HEADER_HGT - FONT_HGT_SMALL) / 2, 0.7, 0.7, 0.7, 1, UIFont.Small)
        return y + HEADER_HGT
    end
    local h = list.itemheight
    local width = list:getWidth()
    if list.selected == item.index then
        list:drawRect(0, y, width, h - 1, 0.3, 0.7, 0.35, 0.15)
    elseif list.mouseoverselected == item.index and list:isMouseOver() then
        list:drawRect(0, y, width, h - 1, 0.1, 1, 1, 1)
    end
    if slot.main then
        list:drawRect(0, y, 3, h - 1, 0.8, 0.85, 0.65, 0.2)
    end
    list:drawRectBorder(0, y, width, h, 0.5, list.borderColor.r, list.borderColor.g, list.borderColor.b)

    -- Character picture: the thumbnail the game takes when it saves.
    local tx, ty = 8, y + (h - THUMB) / 2
    if slot.thumb == nil and not slot.thumbMissing then
        local ok, tex = pcall(SaveSlotsJava.thumbnail, slot.rel)
        if ok and tex then slot.thumb = tex else slot.thumbMissing = true end
    end
    list:drawRect(tx, ty, THUMB, THUMB, 1, 0.08, 0.08, 0.08)
    if slot.thumb then
        list:drawTextureScaled(slot.thumb, tx, ty, THUMB, THUMB, 1, 1, 1, 1)
    end
    list:drawRectBorder(tx, ty, THUMB, THUMB, 0.6, 0.5, 0.5, 0.5)

    local textX = tx + THUMB + 12
    local lineY = y + 6
    local title = slot.main and getText("UI_SaveSlots_MainWorld", slot.name) or slot.name
    local r, g, b = 0.95, 0.95, 0.95
    if slot.main then r, g, b = 0.95, 0.85, 0.55 end
    local titleX = textX
    local auto = slot.info.auto
    if isAutoSlot(slot) then
        local icon = slotIcon(auto)
        if icon then
            list:drawTextureScaled(icon, textX, lineY + (FONT_HGT_SMALL - ICON_SIZE) / 2, ICON_SIZE, ICON_SIZE, 1, 1, 1, 1)
        end
        titleX = textX + ICON_SIZE + 6
    end
    list:drawText(title, titleX, lineY, r, g, b, 1, UIFont.Small)
    local tagX = titleX + getTextManager():MeasureStringX(UIFont.Small, title) + 10
    if isAutoSlot(slot) then
        local tag = getText(auto == "sleep" and "UI_SaveSlots_TagSleep" or "UI_SaveSlots_TagAuto")
        local tr, tg, tb = 0.45, 0.7, 1
        if auto == "sleep" then tr, tg, tb = 0.75, 0.55, 1 end
        list:drawText(tag, tagX, lineY, tr, tg, tb, 1, UIFont.Small)
        tagX = tagX + getTextManager():MeasureStringX(UIFont.Small, tag) + 10
    end
    if slot.current then
        list:drawText(getText("UI_SaveSlots_Playing"), tagX, lineY, 0.45, 0.85, 0.45, 1, UIFont.Small)
    end
    lineY = lineY + FONT_HGT_SMALL + 4

    -- Built once per slot, not every frame.
    local info = slot.info
    if slot.detailsText == nil then
        local details = {}
        if info.char then
            local prof = professionName(info)
            table.insert(details, prof and (info.char .. " (" .. prof .. ")") or info.char)
        end
        if info.days then table.insert(details, getText("UI_SaveSlots_Day", info.days)) end
        if info.kills then table.insert(details, getText("UI_SaveSlots_Kills", info.kills)) end
        slot.detailsText = table.concat(details, "  -  ")
        slot.detailsWidth = getTextManager():MeasureStringX(UIFont.Small, slot.detailsText)
    end
    list:drawText(slot.detailsText, textX, lineY, 0.75, 0.75, 0.75, 1, UIFont.Small)
    local stateText, sr, sg, sb = nil, 0, 0, 0
    if slot.dead then
        stateText, sr, sg, sb = getText("UI_SaveSlots_Dead"), 0.9, 0.3, 0.3
    elseif info.infected == "1" then
        stateText, sr, sg, sb = getText("UI_SaveSlots_Infected"), 0.9, 0.55, 0.2
    end
    if stateText then
        local sx = textX + slot.detailsWidth + 12
        list:drawText(stateText, sx, lineY, sr, sg, sb, 1, UIFont.Small)
    end
    lineY = lineY + FONT_HGT_SMALL + 4

    -- The save being played is the live game: the game keeps saving into it,
    -- so the time and details it was made with no longer apply.
    local saved = slot.current and getText("UI_SaveSlots_LiveSave") or getText("UI_SaveSlots_SavedAt", slot.when)
    local size = formatSize(slot.size)
    if size then saved = saved .. "  -  " .. size end
    list:drawText(saved, textX, lineY, 0.55, 0.55, 0.55, 1, UIFont.Small)
    return y + h
end

function SaveSlotsDialog:prerender()
    ISPanel.prerender(self)
    local footer = getText("UI_SaveSlots_Footer", self.slotCount or 0, formatSize(self.totalSize or 0) or "0 MB")
    local nextBackup, waiting = minutesToNextBackup()
    if waiting then
        footer = footer .. "  -  " .. getText("UI_SaveSlots_FooterAutoWaiting", formatSize(self.autoSize or 0) or "0 MB")
    elseif nextBackup then
        footer = footer .. "  -  " .. getText("UI_SaveSlots_FooterAuto", formatSize(self.autoSize or 0) or "0 MB", nextBackup)
    else
        footer = footer .. "  -  " .. getText("UI_SaveSlots_FooterAutoOff")
    end
    self:drawText(footer, PAD, self.footerY, 0.6, 0.6, 0.6, 1, UIFont.Small)
    if self.folderText then
        local r, g, b = 0.6, 0.6, 0.6
        if self.folderBad then r, g, b = 0.9, 0.45, 0.3 end
        self:drawText(self.folderText, PAD, self.footerY + FONT_HGT_SMALL + 4, r, g, b, 1, UIFont.Small)
    end
end

function SaveSlotsDialog:onSelectSlot(slot)
    -- Section titles cannot be selected.
    if slot.header then
        self.list.selected = self.lastSelected or 0
        return
    end
    self.lastSelected = self.list.selected
    if not slot.main and not isAutoSlot(slot) then
        self.entry:setText(slot.name)
    end
    self:updateButtons()
end

function SaveSlotsDialog:findSlot(cleanName)
    local wanted = string.lower(cleanName)
    for _, slot in ipairs(self.slots) do
        if not slot.main and string.lower(slot.name) == wanted then
            return slot
        end
    end
    return nil
end

--- Shows a yes/no question centered on the window; onYes runs only on YES.
function SaveSlotsDialog:ask(text, onYes)
    if self.modal then return end
    local modal = ISModalDialog:new(self:getAbsoluteX(), self:getAbsoluteY(), 300, 120, text, true, self,
        function(target, button)
            target.modal = nil
            if button.internal == "YES" then onYes() end
        end, self.player)
    modal:initialise()
    modal:setX(self:getAbsoluteX() + (self.width - modal:getWidth()) / 2)
    modal:setY(self:getAbsoluteY() + (self.height - modal:getHeight()) / 2)
    modal:addToUIManager()
    modal:setAlwaysOnTop(true)
    self.modal = modal
end

function SaveSlotsDialog:onSave()
    if self.modal then return end
    local playerObj = getSpecificPlayer(self.player)
    local clean = SaveSlotsJava.cleanName(self.entry:getText())
    if clean == "" then
        say(playerObj, getText("UI_SaveSlots_ErrEmptyName"), false)
        return
    end
    local existing = self:findSlot(clean)
    if isAutoSlot(existing) then
        say(playerObj, getText("UI_SaveSlots_ErrAutoSlot", existing.name), false)
        return
    end
    if existing and not existing.current then
        -- Overwriting needs an explicit yes; nothing is written before that.
        self:ask(getText("UI_SaveSlots_ConfirmOverwrite", existing.name, existing.when), function()
            -- Stay paused until the copy is done.
            SaveSlots.save(existing.name, true, nil, self:close(true))
        end)
        return
    end
    SaveSlots.save(clean, existing ~= nil, nil, self:close(true))
end

function SaveSlotsDialog:onLoad()
    local slot = self:selectedSlot()
    if not slot or slot.current then return end
    local name = slot.main and getText("UI_SaveSlots_MainWorld", slot.name) or slot.name
    self:ask(getText("UI_SaveSlots_ConfirmLoad", name), function()
        self:close()
        SaveSlots.load(slot.rel)
    end)
end

function SaveSlotsDialog:onRename()
    local slot = self:selectedSlot()
    if not slot or slot.main or slot.current or isAutoSlot(slot) or self.modal then return end
    local w, h = 360, 140
    local box = ISTextBox:new(self:getAbsoluteX() + (self.width - w) / 2, self:getAbsoluteY() + (self.height - h) / 2,
        w, h, getText("UI_SaveSlots_RenameTitle", slot.name), slot.name, self,
        function(target, button)
            target.modal = nil
            if button.internal ~= "OK" then return end
            local result = SaveSlotsJava.renameSlot(slot.name, button.parent.entry:getText())
            if result and result:sub(1, 3) == "OK:" then
                target:refreshList()
            else
                say(getSpecificPlayer(target.player), errorText(result or "ERR:unknown"), false)
            end
        end, self.player)
    box:initialise()
    box:addToUIManager()
    box:setAlwaysOnTop(true)
    box.entry:focus()
    box.entry:selectAll()
    self.modal = box
end

function SaveSlotsDialog:onDelete()
    local slot = self:selectedSlot()
    if not slot or slot.main or slot.current then return end
    self:ask(getText("UI_SaveSlots_ConfirmDelete", slot.name, formatSize(slot.size) or "?"), function()
        local result = SaveSlotsJava.deleteSlot(slot.name)
        if result and result:sub(1, 3) == "OK:" then
            say(getSpecificPlayer(self.player), getText("UI_SaveSlots_Deleted", slot.name), true)
            self:refreshList()
        else
            say(getSpecificPlayer(self.player), errorText(result or "ERR:unknown"), false)
        end
    end)
end

function SaveSlotsDialog:onCancel()
    self:close()
end

--- Closes the window and unpauses the game. With keepPaused the game stays
--- paused and the speed to restore later is returned instead.
function SaveSlotsDialog:close(keepPaused)
    if self.modal then
        self.modal:setVisible(false)
        self.modal:removeFromUIManager()
        self.modal = nil
    end
    self:setVisible(false)
    self:removeFromUIManager()
    if SaveSlots.dialog == self then
        SaveSlots.dialog = nil
    end
    local previous = self.previousSpeed
    self.previousSpeed = nil
    if keepPaused then return previous end
    resumeGame(previous)
end

function SaveSlots.openDialog()
    if SaveSlots.dialog then return end
    local player = getPlayer()
    if not player then return end
    if not javaReady() then
        say(player, getText("UI_SaveSlots_ErrNoJava"), false)
        return
    end
    if not SaveSlotsJava.isAvailable() then
        say(player, getText("UI_SaveSlots_ErrMultiplayer"), false)
        return
    end
    applySaveFolder(true)

    local width = math.min(760, getCore():getScreenWidth() - 40)
    local height = math.min(620, getCore():getScreenHeight() - 40)
    local x = (getCore():getScreenWidth() - width) / 2
    local y = (getCore():getScreenHeight() - height) / 2
    local dialog = SaveSlotsDialog:new(x, y, width, height, player:getPlayerNum())
    -- Nothing can happen to the character while the window is open.
    dialog.previousSpeed = pauseGame()
    dialog:initialise()
    dialog:addToUIManager()
    dialog:setAlwaysOnTop(true)
    dialog.entry:focus()
    dialog.entry:selectAll()
    SaveSlots.dialog = dialog
end

----------------------------------------------------------------------------
-- Hotkey
----------------------------------------------------------------------------

local function onKeyPressed(key)
    if key ~= keyOption:getValue() or key == 0 then return end
    -- Works while paused too, but not behind the Esc menu.
    if MainScreen and MainScreen.instance and MainScreen.instance:isVisible() then return end
    if SaveSlots.dialog then
        SaveSlots.dialog:close()
        return
    end
    SaveSlots.openDialog()
end

Events.OnKeyPressed.Add(onKeyPressed)

----------------------------------------------------------------------------
-- "SAVE AS..." entry in the in-game Esc menu, right below OPTIONS.
----------------------------------------------------------------------------

local function openDialogNextTick()
    local function once()
        Events.OnTick.Remove(once)
        SaveSlots.openDialog()
    end
    Events.OnTick.Add(once)
end

local function onSaveAsMenuItem(item, x, y)
    getSoundManager():playUISound("UIActivateMainMenuItem")
    -- Same as RETURN TO GAME, then open the dialog once the game is back.
    ToggleEscapeMenu(getCore():getKey(KeybindId.MAIN_MENU))
    openDialogNextTick()
end

local function addSaveAsMenuItem(screen)
    local optionsLabel = screen.optionsOption
    if not (screen.inGame and optionsLabel and screen.bottomPanel) then return end
    if not (javaReady() and SaveSlotsJava.isAvailable()) then return end

    local insertY = optionsLabel:getBottom()
    local shift = optionsLabel:getHeight()
    for _, child in pairs(screen.bottomPanel:getChildren()) do
        if child.Type == "ISLabel" and child:getY() >= insertY then
            child:setY(child:getY() + shift)
        end
    end

    local label = ISLabel:new(optionsLabel:getX(), insertY, shift, getText("UI_SaveSlots_MenuItem"),
        1, 1, 1, 1, UIFont.Large, true)
    label.internal = "SAVESLOTS_SAVE_AS"
    label:initialise()
    label:setWidth(optionsLabel:getWidth())
    label.onMouseDown = onSaveAsMenuItem
    label.fade = UITransition.new()
    label.fade:setFadeIn(false)
    label.prerender = MainScreen.prerenderBottomPanelLabel
    screen.bottomPanel:addChild(label)
    screen.bottomPanel:setHeight(screen.bottomPanel:getHeight() + shift)
    screen.saveSlotsOption = label
end

require "OptionScreens/MainScreen"

local originalInstantiate = MainScreen.instantiate
function MainScreen:instantiate(...)
    local result = originalInstantiate(self, ...)
    local ok, err = pcall(addSaveAsMenuItem, self)
    if not ok then
        print("[SaveSlots] Could not add the Esc menu item: " .. tostring(err))
    end
    return result
end

----------------------------------------------------------------------------
-- Folder picker for the save folder option (Browse... in the mod options).
-- Drawn by the game, so it is seen in fullscreen too.
----------------------------------------------------------------------------

SaveSlotsFolderPicker = ISPanel:derive("SaveSlotsFolderPicker")

local FOLDER_REASONS = { bad_path = true, not_absolute = true, in_saves = true, onedrive = true,
    not_writable = true, missing = true, not_readable = true }

local function folderError(result)
    local reason = result:match("^ERR:([^:]+)")
    if FOLDER_REASONS[reason] then return getText("UI_SaveSlots_FolderWhy_" .. reason) end
    return errorText(result)
end

--- onPicked(path) runs when a usable folder is selected.
function SaveSlotsFolderPicker:new(x, y, width, height, onPicked)
    local o = ISPanel.new(self, x, y, width, height)
    o.backgroundColor = { r = 0, g = 0, b = 0, a = 0.95 }
    o.borderColor = { r = 0.4, g = 0.4, b = 0.4, a = 1 }
    o.moveWithMouse = true
    o.onPicked = onPicked
    o.path = ""
    return o
end

function SaveSlotsFolderPicker:createChildren()
    ISPanel.createChildren(self)
    local y = PAD
    local title = ISLabel:new(PAD, y, FONT_HGT_MEDIUM, getText("UI_SaveSlots_PickerTitle"), 1, 1, 1, 1, UIFont.Medium, true)
    title:initialise()
    self:addChild(title)
    y = y + FONT_HGT_MEDIUM + PAD / 2
    self.pathY = y
    y = y + FONT_HGT_SMALL + 4

    local bottomArea = (FONT_HGT_SMALL + 4) * 2 + PAD + BUTTON_HGT + PAD
    self.list = ISScrollingListBox:new(PAD, y, self.width - PAD * 2, self.height - y - bottomArea)
    self.list:initialise()
    self.list:instantiate()
    self.list.font = UIFont.Small
    self.list.fontHgt = FONT_HGT_SMALL
    self.list.itemheight = FONT_HGT_SMALL + 8
    self.list.drawBorder = true
    self.list:setOnMouseDownFunction(self, SaveSlotsFolderPicker.updateButtons)
    self.list:setOnMouseDoubleClick(self, SaveSlotsFolderPicker.onOpenItem)
    self:addChild(self.list)
    self.infoY = self.list:getBottom() + PAD / 2

    local w = 120
    local by = self.height - PAD - BUTTON_HGT
    self.newButton = addButton(self, PAD, by, w, getText("UI_SaveSlots_PickerNewFolder"), SaveSlotsFolderPicker.onNewFolder)
    self.cancelButton = addButton(self, self.width - PAD - w, by, w, getText("UI_SaveSlots_CloseButton"), SaveSlotsFolderPicker.close)
    self.selectButton = addButton(self, self.width - PAD * 2 - w * 2, by, w, getText("UI_SaveSlots_PickerSelect"), SaveSlotsFolderPicker.onSelect)
end

--- Shows the folders in path ("" for the drives). False if it cannot be read.
function SaveSlotsFolderPicker:open(path)
    local text = SaveSlotsJava.listFolders(path or "") or ""
    local lines = {}
    for line in (text .. "\n"):gmatch("([^\n]*)\n") do table.insert(lines, line) end
    if not lines[1] or lines[1]:sub(1, 3) ~= "OK:" then
        self.error = folderError(lines[1] or "ERR:not_readable")
        return false
    end
    self.path = lines[1]:sub(4)
    self.list:clear()
    if self.path ~= "" then
        self.list:addItem(getText("UI_SaveSlots_PickerUp"), { path = lines[2] or "", up = true })
    end
    for i = 3, #lines do
        local name, full = lines[i]:match("^([^\t]*)\t(.*)$")
        if name then self.list:addItem(name, { path = full }) end
    end
    self.list.selected = 0
    self.error = nil
    self:updateButtons()
    return true
end

--- The folder Select would pick: the one clicked in the list, or the one open.
function SaveSlotsFolderPicker:target()
    local item = self.list.items[self.list.selected]
    if item and not item.item.up then return item.item.path end
    return self.path
end

function SaveSlotsFolderPicker:updateButtons()
    self.selectButton:setEnable(self:target() ~= "")
    self.newButton:setEnable(self.path ~= "")
end

function SaveSlotsFolderPicker:onOpenItem(item)
    self:open(item.path)
end

function SaveSlotsFolderPicker:onSelect()
    local path = self:target()
    if path == "" then return end
    local result = SaveSlotsJava.checkFolder(path) or "ERR:bad_path"
    if result:sub(1, 3) ~= "OK:" then
        self.error = folderError(result)
        return
    end
    self:close()
    self.onPicked(result:sub(4))
end

function SaveSlotsFolderPicker:onNewFolder()
    if self.path == "" or self.modal then return end
    local w, h = 360, 140
    local box = ISTextBox:new(self:getAbsoluteX() + (self.width - w) / 2, self:getAbsoluteY() + (self.height - h) / 2,
        w, h, getText("UI_SaveSlots_PickerNewFolderTitle"), "PZ Saves", self,
        function(target, button)
            target.modal = nil
            if button.internal ~= "OK" then return end
            local result = SaveSlotsJava.makeFolder(target.path, button.parent.entry:getText()) or "ERR:bad_path"
            if result:sub(1, 3) == "OK:" then
                target:open(result:sub(4))
            else
                target.error = folderError(result)
            end
        end)
    box:initialise()
    box:addToUIManager()
    box:setAlwaysOnTop(true)
    box.entry:focus()
    box.entry:selectAll()
    self.modal = box
end

function SaveSlotsFolderPicker:prerender()
    ISPanel.prerender(self)
    local shown = self.path ~= "" and self.path or getText("UI_SaveSlots_PickerDrives")
    self:drawText(shown, PAD, self.pathY, 0.8, 0.8, 0.8, 1, UIFont.Small)
    local target = self:target()
    if target ~= "" then
        self:drawText(getText("UI_SaveSlots_FolderChosen", target), PAD, self.infoY, 0.6, 0.6, 0.6, 1, UIFont.Small)
    end
    if self.error then
        self:drawText(self.error, PAD, self.infoY + FONT_HGT_SMALL + 4, 0.9, 0.45, 0.3, 1, UIFont.Small)
    end
end

function SaveSlotsFolderPicker:close()
    if self.modal then
        self.modal:removeFromUIManager()
        self.modal = nil
    end
    self:setVisible(false)
    self:removeFromUIManager()
    SaveSlotsFolderPicker.instance = nil
end

--- Opens the picker at startPath (or the drives if it cannot be read).
function SaveSlotsFolderPicker.show(startPath, onPicked)
    if SaveSlotsFolderPicker.instance then SaveSlotsFolderPicker.instance:close() end
    local width = math.min(560, getCore():getScreenWidth() - 40)
    local height = math.min(460, getCore():getScreenHeight() - 40)
    local picker = SaveSlotsFolderPicker:new((getCore():getScreenWidth() - width) / 2,
        (getCore():getScreenHeight() - height) / 2, width, height, onPicked)
    picker:initialise()
    picker:addToUIManager()
    picker:setAlwaysOnTop(true)
    if not picker:open(startPath) then
        local why = picker.error
        picker:open("")
        picker.error = why
    end
    SaveSlotsFolderPicker.instance = picker
end

local function onBrowseFolder()
    if not (javaReady() and SaveSlotsJava.listFolders) then return end
    -- What is in the box now, even if not applied yet.
    local start = folderOption.element and folderOption.element:getInternalText() or folderOption:getValue()
    SaveSlotsFolderPicker.show(start, function(path)
        folderOption:setValue(path)
        folderOnOption:setValue(true)
        -- Kept even if the options screen is left with Back.
        PZAPI.ModOptions:save()
    end)
end

-- Last, so it shows right below the folder box.
options:addButton("browseSaveFolder", getText("UI_SaveSlots_PickerButton"), getText("UI_SaveSlots_PickerButton_tooltip"),
    onBrowseFolder)
