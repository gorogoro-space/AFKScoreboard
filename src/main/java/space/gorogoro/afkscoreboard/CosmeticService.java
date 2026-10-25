package space.gorogoro.afkscoreboard;

import com.destroystokyo.paper.event.entity.EntityZapEvent;
import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Keyed;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.type.HangingMoss;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Axolotl;
import org.bukkit.entity.Bee;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Camel;
import org.bukkit.entity.Cat;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Cow;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Fox;
import org.bukkit.entity.Frog;
import org.bukkit.entity.Goat;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.MushroomCow;
import org.bukkit.entity.Panda;
import org.bukkit.entity.Parrot;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Player;
import org.bukkit.entity.PolarBear;
import org.bukkit.entity.PufferFish;
import org.bukkit.entity.Rabbit;
import org.bukkit.entity.Salmon;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.Sittable;
import org.bukkit.entity.Slime;
import org.bukkit.entity.Sniffer;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Wolf;
import org.bukkit.entity.ZombieNautilus;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.CreeperPowerEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.EntityDropItemEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerBucketEntityEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * ゾーン内だけの見た目。パーティクルは間隔を空け、頭上のブロックと MOB は乗客なので毎 tick 動かさない。
 * エンダードラゴンは入れない。
 */
final class CosmeticService implements Listener {

    private final AFKScoreboard plugin;
    private final CosmeticStore store;
    private final NamespacedKey tagKey;
    private final NamespacedKey ownerKey;
    // 座っている間、足元のブロックを上げる量(腰の高さ)。床に直接座ると少し浮くかもしれないので実機で調整する
    private static final float SEATED_FLOOR_LIFT = 0.75f;
    // 名前を出す高さ(頭の上からのずらし)。バニラのネームタグとほぼ同じ位置。実機で調整する
    private static final float NAME_TAG_LIFT = 0.25f;
    // 鶏の上に名前を 2 段で乗せるときの高さ。本番の統合版で表示を確かめた値
    private static final float CHICKEN_NAME_TAG_LIFT = 0.6f;
    // 立っている人を見始めた統合版の人に、こちらの名前を見せるまで待つ時間。統合版は約 5〜8 秒で本人の名前を隠す(本番で確認)
    private static final long STANDING_NAME_DELAY_TICKS = 120L;

    private final Map<UUID, Active> active = new HashMap<>();
    // Floodgate が無ければ null。そのときは統合版向けの名前の切り替えをしない
    private final BedrockPlayers bedrock;
    // 頭上 MOB を乗せたまま乗り物に乗った人。統合版は降りても本人の名前を隠したままにするので、降りたときに見せ直す
    private final Set<UUID> seatedWithRider = new HashSet<>();
    // 頭上 MOB の出現を止められている人。警告は失敗が続く間に 1 回だけ出し、出せたら外す
    private final Set<UUID> mountSpawnFailed = new HashSet<>();
    // resendToBedrock で出し直している間は true。そのときの PlayerTrackEntityEvent は自分が起こしたものなので見ない
    private boolean resending;
    // 見た目を付け直さない人と、付け直してよくなる tick。別ワールドへのテレポートを乗客が邪魔しないように
    private final Map<UUID, Integer> pausedUntil = new HashMap<>();
    // 他のプラグインに降ろされた見た目を、次の tick に消す予約がある人
    private final Set<UUID> dismountClearPending = new HashSet<>();
    // teleport-commands の直後に待つ時間。CoreProtect は読み込み済みのチャンクへ次の tick で飛ぶ
    private static final int COMMAND_PAUSE_TICKS = 10;
    // 他のプラグインに見た目を降ろされてから、テレポートを待つ最長の時間。チャンクの生成に数秒かかることがある
    private static final int DISMOUNT_PAUSE_TICKS = 200;
    // 乗客を降ろさずにテレポートするコマンド。config.yml の teleport-commands を起動時に読む
    private final List<String> teleportCommands = new ArrayList<>();
    // 自分で見た目を外している間は true。そのときの降車は、他のプラグインのテレポートとして扱わない
    private boolean selfDismounting;
    private int weekClock;

    CosmeticService(AFKScoreboard plugin) {
        this.plugin = plugin;
        this.store = new CosmeticStore(plugin);
        this.tagKey = new NamespacedKey(plugin, "cosmetic");
        this.ownerKey = new NamespacedKey(plugin, "owner");
        this.bedrock = BedrockPlayers.detect(plugin);
    }

    void load() {
        store.load();
        teleportCommands.clear();
        for (String entry : plugin.getConfig().getStringList("teleport-commands")) {
            String normalized = normalizeCommand(entry);
            if (!normalized.isEmpty()) {
                teleportCommands.add(normalized);
            }
        }
    }

    /**
     * 小文字にし、先頭の / と「プラグイン名:」を取り、空白を 1 つにまとめる。
     */
    private static String normalizeCommand(String text) {
        String trimmed = text.trim().toLowerCase(java.util.Locale.ROOT);
        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        String[] parts = trimmed.split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) {
            return "";
        }
        parts[0] = parts[0].substring(parts[0].indexOf(':') + 1);
        return String.join(" ", parts);
    }

    void requestSave() {
        store.requestSave();
    }

    void shutdown() {
        removeAll();
        store.shutdown();
    }

    void removeStrayEntities() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (isOurs(entity)) {
                    removeQuietly(entity);
                }
            }
        }
    }

    void removeAll() {
        for (UUID uuid : new ArrayList<>(active.keySet())) {
            clear(Bukkit.getPlayer(uuid), uuid);
        }
        removeStrayEntities();
    }

    /**
     * 1 秒に 1 回。ゾーン内の秒数を足し、見た目を合わせる。ゾーン外とログアウトでは消す。
     * /afkhide で非表示中の人とスペクテイター・バニッシュ中の人は、秒数だけ数えて見た目は付けない。
     */
    void maintain() {
        if (++weekClock >= 60) {
            weekClock = 0;
            if (store.rolloverIfNeeded()) {
                removeAll();
            }
        }
        Set<UUID> online = new HashSet<>();
        int now = Bukkit.getCurrentTick();
        pausedUntil.values().removeIf(until -> until <= now);
        for (Player player : Bukkit.getOnlinePlayers()) {
            online.add(player.getUniqueId());
            if (plugin.isPlayerInAnyZone(player.getLocation())) {
                store.addSecond(player.getUniqueId());
                if (pausedUntil.containsKey(player.getUniqueId())) {
                    // コマンドの直後。テレポートが終わるまで付け直さない
                    continue;
                }
                if (plugin.isHidden(player.getUniqueId()) || plugin.isConcealed(player)) {
                    clear(player);
                } else {
                    sync(player);
                }
            } else {
                clear(player);
            }
        }
        for (UUID uuid : new ArrayList<>(active.keySet())) {
            if (!online.contains(uuid)) {
                clear(null, uuid);
            }
        }
        pausedUntil.keySet().retainAll(online);
        seatedWithRider.retainAll(online);
        mountSpawnFailed.retainAll(online);
    }

    /**
     * デバッグ付与。放置秒数は増やさない。未抽選の枠だけその場で決める。
     * ゾーン内ならすぐに出し、ゾーン外では入ったときに出す。
     */
    DebugGrant debugGrant(Player player, boolean particle, boolean block, boolean mount) {
        CosmeticStore.Record record = store.record(player.getUniqueId());
        DebugGrant grant = new DebugGrant();
        boolean changed = false;
        if (particle) {
            grant.particleNew = CosmeticKinds.ParticleKind.parse(record.particle) == null;
            if (grant.particleNew) {
                record.particle = CosmeticKinds.ParticleKind.random().name();
                changed = true;
            }
            grant.particle = record.particle;
        }
        if (block) {
            grant.blockNew = CosmeticKinds.BlockKind.parse(record.block) == null;
            if (grant.blockNew) {
                record.block = CosmeticKinds.BlockKind.random().name();
                changed = true;
            }
            grant.block = record.block;
        }
        if (mount) {
            grant.mountNew = CosmeticKinds.MountKind.parse(record.mount) == null;
            if (grant.mountNew) {
                record.mount = CosmeticKinds.MountKind.random().name();
                record.mountVariant = null;
                changed = true;
            }
            grant.mount = record.mount;
        }
        if (changed) {
            store.markDirty();
            store.requestSave();
        }
        grant.inZone = plugin.isPlayerInAnyZone(player.getLocation());
        grant.hidden = plugin.isHidden(player.getUniqueId());
        grant.concealed = plugin.isConcealed(player);
        if (grant.inZone && !grant.hidden && !grant.concealed) {
            sync(player);
            Active state = active.get(player.getUniqueId());
            if (state != null && state.particle != null) {
                spawnParticle(player, state.particle);
            }
        }
        return grant;
    }

    /**
     * /afklook reset。見た目をすべて外す。今週の放置秒数は残すので、条件を満たしている枠は次の 1 秒で引き直される。
     */
    boolean debugClear(Player player) {
        CosmeticStore.Record record = store.record(player.getUniqueId());
        boolean had = CosmeticKinds.ParticleKind.parse(record.particle) != null
                || CosmeticKinds.BlockKind.parse(record.block) != null
                || CosmeticKinds.MountKind.parse(record.mount) != null;
        record.particle = null;
        record.block = null;
        record.mount = null;
        record.mountVariant = null;
        if (had) {
            store.markDirty();
            store.requestSave();
        }
        clear(player);
        return had;
    }

    /**
     * /afklook。指定した種類の表示と非表示を切り替え、その場で見た目を合わせる。
     * 複数指定(all)のときは、1 つでも表示中なら全部を非表示に、全部非表示なら全部を表示にする。
     * @return 切り替え後に非表示なら true
     */
    boolean toggleLook(Player player, List<CosmeticStore.Slot> slots) {
        UUID uuid = player.getUniqueId();
        boolean anyOn = false;
        for (CosmeticStore.Slot slot : slots) {
            if (!store.isOff(uuid, slot)) {
                anyOn = true;
                break;
            }
        }
        for (CosmeticStore.Slot slot : slots) {
            store.setOff(uuid, slot, anyOn);
        }
        refresh(player);
        return anyOn;
    }

    /**
     * /afkhide・/afklook の切り替え直後に、次の 1 秒を待たずに見た目を合わせる。秒数は足さない。
     */
    void refresh(Player player) {
        if (plugin.isPlayerInAnyZone(player.getLocation()) && !plugin.isHidden(player.getUniqueId()) && !plugin.isConcealed(player)) {
            sync(player);
        } else {
            clear(player);
        }
    }

    /**
     * 頭上の MOB の向き(左右・上下)をプレイヤーに合わせる。位置は動かさない。
     * PlayerMoveEvent から、向きが変わったときだけ呼ばれる。MOB がいない人は Map を引いて抜ける。
     */
    void syncRotation(Player player, Location to) {
        Active state = active.get(player.getUniqueId());
        if (state == null || state.rider == null || !state.rider.isValid()) {
            return;
        }
        faceMount(state.rider, to.getYaw(), to.getPitch());
    }

    /**
     * 頭上の MOB をプレイヤーの向きに合わせる。位置は動かさない。
     * ヤギは setYHeadRot が体から ±15° までしか頭を許さない。体を先に同じヨーにしないと、
     * 頭の回転がそこまでしか届かず、立ち止まって振り向いたとき体が止まったままになる。
     */
    private static void faceMount(LivingEntity living, float yaw, float pitch) {
        if (living instanceof Goat) {
            living.setBodyYaw(yaw);
        }
        living.setRotation(yaw, pitch);
    }

    /**
     * 3 tick に 1 回。何かに乗っている(GSit で座っているなど)人について、
     * 足元のブロック(花びら、キノコと枯れ木の地面側)を座面の高さに上げ下げし、頭上の MOB の向きを合わせる。
     * 乗っている間は PlayerMoveEvent が来ないため。見るのは足元ブロックか頭上 MOB がある人だけ。
     */
    void tickSeated() {
        for (Map.Entry<UUID, Active> entry : active.entrySet()) {
            Active state = entry.getValue();
            boolean hasFloor = !state.floorDisplays.isEmpty();
            boolean hasRider = state.rider != null && state.rider.isValid();
            // 座ったまま MOB を外した人も見る(統合版の本人の名前が隠れたままになるため)
            if (!hasFloor && !hasRider && state.seatedRider == null) {
                continue;
            }
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) {
                continue;
            }
            boolean seated = player.isInsideVehicle();
            syncSeatedRider(player, state, seated, hasRider);
            if (hasRider) {
                syncBedrockView(player, state);
                if (seated && bedrock != null) {
                    seatedWithRider.add(entry.getKey());
                }
            }
            // 座った・立ったときだけ高さを切り替える
            if (hasFloor && seated != state.floorLifted) {
                liftDisplays(state, seated ? SEATED_FLOOR_LIFT : -SEATED_FLOOR_LIFT);
                state.floorLifted = seated;
            }
            if (!hasRider || !seated) {
                continue;
            }
            Location look = player.getLocation();
            Location current = state.rider.getLocation();
            if (look.getYaw() == current.getYaw() && look.getPitch() == current.getPitch()) {
                continue;
            }
            faceMount(state.rider, look.getYaw(), look.getPitch());
        }
        // 見た目を外したあとに降りた人も含めて見る(統合版の本人の名前は、見た目の有無に関係なく隠れたまま)
        if (!seatedWithRider.isEmpty()) {
            seatedWithRider.removeIf(uuid -> {
                Player player = Bukkit.getPlayer(uuid);
                if (player == null) {
                    return true;
                }
                if (player.isInsideVehicle()) {
                    return false;
                }
                // 頭上 MOB がいれば、立っていてもこちらの名前を統合版に見せるので、本人の名前は隠れたままでよい。
                // 本人を出し直すとスキンが外れる(スティーブになる)ことがあるので、MOB がいなくなった人だけ出し直す
                Active state = active.get(uuid);
                if (state == null || state.rider == null || !state.rider.isValid()) {
                    resendToBedrock(player);
                }
                return true;
            });
        }
    }

    /**
     * 統合版は、乗り物に乗った情報を受け取った時点で頭に乗客がいれば本人の名前を隠し、次に受け取るまで変えない。
     * 座ったまま頭上 MOB が出た・消えたら、統合版の人に出し直して今の状態で判定し直させる。
     * seatedRider は、統合版が判定したときに MOB がいたか。null は座っていないか、まだわからない(再読み込み直後など)。
     */
    private void syncSeatedRider(Player player, Active state, boolean seated, boolean hasRider) {
        if (bedrock == null) {
            return;
        }
        if (!seated) {
            state.seatedRider = null;
            return;
        }
        if (state.seatedRider == null && !hasRider) {
            // MOB なしで座っていれば、統合版は本人の名前を出している
            state.seatedRider = false;
            return;
        }
        if (state.seatedRider == null || state.seatedRider != hasRider) {
            resendToBedrock(player);
            state.seatedRider = hasRider;
        }
    }

    /**
     * 統合版の人に、このプレイヤーの乗り物に乗った情報を送り直し、今の乗客の状態で本人の名前を決め直させる。
     * 座っていれば乗り物(GSit の座席など)を出し直す。本人を出し直すと、タブリストから一度消えてスキンが外れる(スティーブになる)ことがある。
     * 乗り物がなければ本人を出し直す。Paper は出し直すとき、本人の乗客(MOB)を先に、乗り物の情報を後に送る。
     */
    private void resendToBedrock(Player player) {
        // 停止中(onDisable の後片付け)は hidePlayer が例外になり、停止の処理が途中で止まる。次の起動時に出し直すので何もしない
        if (!plugin.isEnabled()) {
            return;
        }
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer != player && bedrock.is(viewer) && viewer.canSee(player)) {
                resendTo(viewer, player);
            }
        }
    }

    private void resendTo(Player viewer, Player player) {
        Entity vehicle = player.getVehicle();
        resending = true;
        try {
            if (vehicle != null) {
                viewer.hideEntity(plugin, vehicle);
                viewer.showEntity(plugin, vehicle);
            } else {
                viewer.hidePlayer(plugin, player);
                viewer.showPlayer(plugin, player);
            }
        } finally {
            resending = false;
        }
    }

    void tickParticles() {
        for (Map.Entry<UUID, Active> entry : active.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            Active state = entry.getValue();
            // スペクテイター・バニッシュになった直後は、次の 1 秒で外れるまでの間も出さない
            if (player == null || !player.isOnline() || state.particle == null || plugin.isConcealed(player)) {
                continue;
            }
            if (!plugin.isPlayerInAnyZone(player.getLocation())) {
                continue;
            }
            spawnParticle(player, state.particle);
        }
    }

    private void sync(Player player) {
        CosmeticStore.Record record = store.record(player.getUniqueId());
        if (unlockIfNeeded(record)) {
            store.markDirty();
            store.requestSave();
        }
        Active state = active.computeIfAbsent(player.getUniqueId(), ignored -> new Active());
        UUID uuid = player.getUniqueId();
        // /afklook で非表示にしている種類は、抽選結果は残したまま付けない
        CosmeticKinds.ParticleKind particle = store.isOff(uuid, CosmeticStore.Slot.PARTICLE)
                ? null : CosmeticKinds.ParticleKind.parse(record.particle);
        state.particle = particle;

        CosmeticKinds.BlockKind block = store.isOff(uuid, CosmeticStore.Slot.BLOCK)
                ? null : CosmeticKinds.BlockKind.parse(record.block);
        if (block == null) {
            removeDisplays(state);
            state.block = null;
        } else if (state.block != block || !hasLiveDisplay(state)) {
            removeDisplays(state);
            spawnDisplays(player, block, state);
            state.block = block;
        } else {
            keepMounted(player, state.displays);
        }

        CosmeticKinds.MountKind mount = store.isOff(uuid, CosmeticStore.Slot.MOUNT)
                ? null : CosmeticKinds.MountKind.parse(record.mount);
        if (mount == null) {
            removeRider(state);
            state.mount = null;
        } else if (state.mount != mount || state.rider == null || !state.rider.isValid() || !player.getPassengers().contains(state.rider)) {
            removeRider(state);
            state.rider = spawnMount(player, mount, record.mountVariant);
            state.mount = mount;
            if (state.rider != null) {
                String variant = readVariant(state.rider);
                if (variant != null && !variant.equals(record.mountVariant)) {
                    record.mountVariant = variant;
                    store.markDirty();
                    store.requestSave();
                }
            }
        } else if (state.rider instanceof MushroomCow cow && record.mountVariant != null
                && !record.mountVariant.equals(cow.getVariant().name())) {
            // ムーシュルームは雷に打たれると色(赤・茶)が入れ替わる。止めるイベントが無いので、抽選した色に戻す
            cow.setVariant(pickEnum(MushroomCow.Variant.class, record.mountVariant));
        }
        syncNameTag(player, state);
    }

    private boolean unlockIfNeeded(CosmeticStore.Record record) {
        boolean changed = false;
        if (record.seconds >= threshold("thresholds.particle-seconds", 1800)
                && CosmeticKinds.ParticleKind.parse(record.particle) == null) {
            record.particle = CosmeticKinds.ParticleKind.random().name();
            changed = true;
        }
        if (record.seconds >= threshold("thresholds.block-seconds", 3600)
                && CosmeticKinds.BlockKind.parse(record.block) == null) {
            record.block = CosmeticKinds.BlockKind.random().name();
            changed = true;
        }
        if (record.seconds >= threshold("thresholds.mount-seconds", 10800)
                && CosmeticKinds.MountKind.parse(record.mount) == null) {
            record.mount = CosmeticKinds.MountKind.random().name();
            record.mountVariant = null;
            changed = true;
        }
        return changed;
    }

    private int threshold(String path, int fallback) {
        int value = plugin.getConfig().getInt(path);
        return value > 0 ? value : fallback;
    }

    private void clear(Player player) {
        if (player == null) {
            return;
        }
        clear(player, player.getUniqueId());
    }

    private void clear(Player player, UUID uuid) {
        Active state = active.remove(uuid);
        if (state != null) {
            removeDisplays(state);
            removeRider(state);
            removeNameTag(state);
        }
        if (player != null) {
            stripPassengers(player);
            // 座ったまま見た目を全部外すと 1 秒タスクで見なくなるので、ここで統合版の人に出し直す(名前が 0 個のままにならないように)
            if (state != null && Boolean.TRUE.equals(state.seatedRider) && player.isInsideVehicle()) {
                resendToBedrock(player);
            }
        }
    }

    private void spawnParticle(Player player, CosmeticKinds.ParticleKind kind) {
        Location loc = player.getLocation();
        World world = loc.getWorld();
        if (world == null) {
            return;
        }
        switch (kind) {
            case SMOKE -> world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, loc.clone().add(0.0, 0.35, 0.0), 1, 0.12, 0.02, 0.12, 0.0);
            case SPORE -> world.spawnParticle(Particle.SPORE_BLOSSOM_AIR, loc.clone().add(0.0, 1.1, 0.0), 2, 0.35, 0.35, 0.35, 0.0);
            case CHERRY -> world.spawnParticle(Particle.CHERRY_LEAVES, loc.clone().add(0.0, 2.0, 0.0), 1, 0.2, 0.08, 0.2, 0.0);
            case FIREFLY -> world.spawnParticle(Particle.FIREFLY, loc.clone().add(0.0, 1.05, 0.0), 1, 0.4, 0.3, 0.4, 0.0);
            case SNOW -> world.spawnParticle(Particle.SNOWFLAKE, loc.clone().add(0.0, 2.05, 0.0), 1, 0.22, 0.08, 0.22, 0.0);
        }
    }

    private void spawnDisplays(Player player, CosmeticKinds.BlockKind kind, Active state) {
        // 位置はブロックの中心ではなく角が基準
        Offset[] offsets = switch (kind) {
            case COBWEB -> new Offset[] {
                    new Offset(-0.25f, -0.15f, 0.05f, 0.42f),
                    new Offset(0.2f, 0.05f, -0.15f, 0.38f)
            };
            // 肩と背中、脇から葉が生えている。上端は目より下
            case AZALEA -> new Offset[] {
                    new Offset(-0.34f, -0.82f, -0.02f, 0.36f),
                    new Offset(0.06f, -0.78f, 0.12f, 0.32f),
                    new Offset(-0.12f, -0.98f, 0.20f, 0.34f),
                    new Offset(-0.30f, -1.22f, -0.10f, 0.28f),
                    new Offset(0.22f, -1.05f, -0.16f, 0.26f)
            };
            // 散らばり方はそのまま、全体の範囲(x -0.495〜0.495、z -0.515〜0.515)の中心をプレイヤーの真下にする
            case PETALS -> new Offset[] {
                    ground(-0.495f, -1.76f, -0.155f, 0.5f),
                    ground(0.045f, -1.76f, -0.515f, 0.45f),
                    ground(-0.155f, -1.76f, 0.065f, 0.45f)
            };
            // 肩・背中・脚に薄く生えた苔。カーペットは底面だけなので、角の高さが見た目の高さ
            case MOSS -> new Offset[] {
                    sheet(-0.22f, -0.50f, -0.06f, 0.40f, 1.0f, 0.34f, 0f, 0f, 0f),
                    sheet(0.02f, -0.52f, 0.04f, 0.34f, 1.0f, 0.30f, 0f, 0f, 0f),
                    sheet(-0.10f, -0.78f, 0.18f, 0.36f, 1.0f, 0.28f, 0f, 0f, 0f),
                    sheet(-0.08f, -1.28f, -0.05f, 0.32f, 1.0f, 0.28f, 0f, 0f, 0f),
                    sheet(0.12f, -1.18f, 0.08f, 0.28f, 1.0f, 0.24f, 0f, 12f, 0f)
            };
            // 肩と背中に盛り上がった苔の塊。厚みは低くして、貼り付いて生えたようにする
            case MOSS_BLOCK -> new Offset[] {
                    sheet(-0.30f, -0.66f, -0.04f, 0.26f, 0.14f, 0.22f, 0f, 0f, 0f),
                    sheet(0.08f, -0.60f, 0.02f, 0.22f, 0.12f, 0.20f, 0f, 0f, 0f),
                    sheet(-0.08f, -0.90f, 0.18f, 0.28f, 0.14f, 0.18f, 0f, 0f, 0f),
                    sheet(0.22f, -1.14f, -0.08f, 0.16f, 0.16f, 0.20f, 0f, 0f, 0f)
            };
            // 面だけの蔓。胴の横、背中、腹、肩、脚に沿わせる。目の正面は空けておく
            case VINE -> new Offset[] {
                    sheet(-0.42f, -1.35f, -0.18f, 0.08f, 0.85f, 0.40f, 0f, 0f, 0f, BlockFace.WEST, BlockFace.EAST),
                    sheet(0.32f, -1.25f, -0.05f, 0.07f, 0.70f, 0.32f, 0f, 0f, 0f, BlockFace.EAST, BlockFace.WEST),
                    sheet(-0.20f, -0.98f, 0.28f, 0.45f, 0.50f, 0.06f, 0f, 18f, 0f, BlockFace.SOUTH, BlockFace.NORTH),
                    sheet(-0.10f, -1.18f, -0.38f, 0.32f, 0.40f, 0.06f, 0f, -12f, 0f, BlockFace.NORTH, BlockFace.SOUTH),
                    sheet(-0.34f, -0.68f, 0.02f, 0.10f, 0.35f, 0.28f, 0f, 0f, 25f, BlockFace.WEST, BlockFace.EAST),
                    sheet(0.18f, -1.62f, 0.05f, 0.06f, 0.45f, 0.18f, 0f, 0f, 0f, BlockFace.EAST, BlockFace.WEST)
            };
            // ポーション材料の小さい茶キノコ。体からと、足元に複数本
            case BROWN_MUSHROOM -> new Offset[] {
                    new Offset(-0.30f, -0.82f, -0.02f, 0.32f),
                    new Offset(-0.06f, -0.96f, 0.18f, 0.34f),
                    new Offset(0.16f, -1.08f, -0.12f, 0.28f),
                    ground(-0.42f, -1.76f, -0.22f, 0.42f),
                    ground(0.08f, -1.76f, -0.48f, 0.38f),
                    ground(-0.18f, -1.76f, 0.12f, 0.46f),
                    ground(0.32f, -1.76f, 0.02f, 0.36f),
                    ground(-0.05f, -1.76f, -0.08f, 0.32f)
            };
            // ポーション材料の小さい赤キノコ。配置は茶とずらす
            case RED_MUSHROOM -> new Offset[] {
                    new Offset(0.08f, -0.76f, 0.04f, 0.30f),
                    new Offset(-0.22f, -0.90f, 0.16f, 0.32f),
                    new Offset(-0.28f, -1.14f, -0.08f, 0.28f),
                    ground(-0.28f, -1.76f, -0.40f, 0.40f),
                    ground(0.18f, -1.76f, -0.12f, 0.44f),
                    ground(-0.48f, -1.76f, 0.08f, 0.36f),
                    ground(0.05f, -1.76f, 0.28f, 0.42f),
                    ground(0.30f, -1.76f, 0.32f, 0.34f)
            };
            // 枯れ木。肩・背中・脇と、足元に数本。上端は目より下
            case DEAD_BUSH -> new Offset[] {
                    new Offset(-0.32f, -0.90f, -0.02f, 0.42f),
                    new Offset(-0.08f, -1.02f, 0.18f, 0.44f),
                    new Offset(0.16f, -1.16f, -0.08f, 0.36f),
                    ground(-0.36f, -1.76f, -0.16f, 0.48f),
                    ground(0.10f, -1.76f, -0.40f, 0.44f),
                    ground(-0.08f, -1.76f, 0.14f, 0.50f)
            };
            // ペールの垂れ苔。先端の十字を、胴の横・背中・肩・脚に垂らす
            case PALE_HANGING_MOSS -> new Offset[] {
                    sheet(-0.40f, -1.28f, -0.06f, 0.32f, 0.74f, 0.32f, 0f, 0f, 0f),
                    sheet(0.16f, -1.18f, 0.02f, 0.28f, 0.64f, 0.28f, 0f, 0f, 0f),
                    sheet(-0.12f, -1.08f, 0.20f, 0.36f, 0.58f, 0.28f, 0f, 0f, 0f),
                    sheet(-0.30f, -0.80f, 0.00f, 0.26f, 0.34f, 0.26f, 0f, 0f, 0f),
                    sheet(0.12f, -1.62f, 0.04f, 0.24f, 0.46f, 0.22f, 0f, 0f, 0f)
            };
            // ペールオークの葉。開花したツツジの葉と同じ位置
            case PALE_OAK_LEAVES -> new Offset[] {
                    new Offset(-0.34f, -0.82f, -0.02f, 0.36f),
                    new Offset(0.06f, -0.78f, 0.12f, 0.32f),
                    new Offset(-0.12f, -0.98f, 0.20f, 0.34f),
                    new Offset(-0.30f, -1.22f, -0.10f, 0.28f),
                    new Offset(0.22f, -1.05f, -0.16f, 0.26f)
            };
        };
        World world = player.getWorld();
        // 向きを 0 にして出す。プレイヤーの向きを引き継ぐと、ずらし(translation)ごと回転して足元から外れる
        Location spawnAt = player.getLocation();
        spawnAt.setYaw(0.0f);
        spawnAt.setPitch(0.0f);
        for (Offset offset : offsets) {
            BlockDisplay display = world.spawn(spawnAt, BlockDisplay.class, entity -> {
                tag(entity, player.getUniqueId());
                entity.setBlock(blockData(kind, offset.faces()));
                entity.setPersistent(false);
                entity.setInvulnerable(true);
                entity.setGravity(false);
                entity.setBillboard(Display.Billboard.FIXED);
                entity.setViewRange(32.0f);
                entity.setShadowRadius(0.0f);
                entity.setShadowStrength(0.0f);
                entity.setBrightness(new Display.Brightness(15, 15));
                entity.setInterpolationDuration(0);
                Quaternionf rotation = new Quaternionf()
                        .rotateX((float) Math.toRadians(offset.pitch()))
                        .rotateY((float) Math.toRadians(offset.yaw()))
                        .rotateZ((float) Math.toRadians(offset.roll()));
                entity.setTransformation(new Transformation(
                        new Vector3f(offset.x(), offset.y(), offset.z()),
                        rotation,
                        new Vector3f(offset.sx(), offset.sy(), offset.sz()),
                        new Quaternionf()));
            });
            player.addPassenger(display);
            state.displays.add(display);
            if (offset.floor()) {
                state.floorDisplays.add(display);
            }
        }
    }

    private static Offset ground(float x, float y, float z, float scale) {
        return new Offset(x, y, z, scale, scale, scale, 0f, 0f, 0f, null, true);
    }

    private static Offset sheet(float x, float y, float z, float sx, float sy, float sz,
            float pitch, float yaw, float roll, BlockFace... faces) {
        BlockFace[] stored = faces == null || faces.length == 0 ? null : faces;
        return new Offset(x, y, z, sx, sy, sz, pitch, yaw, roll, stored, false);
    }

    private static BlockData blockData(CosmeticKinds.BlockKind kind, BlockFace[] faces) {
        BlockData data = kind.material.createBlockData();
        if (faces != null && data instanceof MultipleFacing facing) {
            for (BlockFace face : faces) {
                facing.setFace(face, true);
            }
        }
        if (data instanceof HangingMoss moss) {
            // 途中の節ではなく先端の房。垂れた見た目になる
            moss.setTip(true);
        }
        return data;
    }

    private LivingEntity spawnMount(Player player, CosmeticKinds.MountKind kind, String variant) {
        // 頭の高さに出す。足元に出すと、統合版は出たばかりの魚の乗車情報を受け付けず、足元の床に残した(入り直すと頭に乗る)
        Location spawnAt = player.getLocation().add(0.0, player.getHeight(), 0.0);
        Entity spawned = player.getWorld().spawnEntity(
                spawnAt,
                kind.entityType,
                CreatureSpawnEvent.SpawnReason.CUSTOM,
                entity -> prepareMount(entity, player, kind, variant));
        if (!spawned.isValid()) {
            // 他のプラグインに出現を止められた。1 秒ごとにやり直すので、失敗が続く間は最初の 1 回だけ知らせる
            if (mountSpawnFailed.add(player.getUniqueId())) {
                plugin.getLogger().warning(String.format(
                        "%s の頭MOB(%s)を出せませんでした。ほかのプラグインに出現を止められた可能性があります(%s %.1f %.1f %.1f)",
                        player.getName(), kind.name(), spawnAt.getWorld().getName(),
                        spawnAt.getX(), spawnAt.getY(), spawnAt.getZ()));
            }
            return null;
        }
        mountSpawnFailed.remove(player.getUniqueId());
        if (!(spawned instanceof LivingEntity living)) {
            spawned.remove();
            return null;
        }
        living.setSilent(true);
        Location look = player.getLocation();
        faceMount(living, look.getYaw(), look.getPitch());
        player.addPassenger(living);
        return living;
    }

    private void prepareMount(Entity entity, Player player, CosmeticKinds.MountKind kind, String variant) {
        tag(entity, player.getUniqueId());
        entity.setPersistent(false);
        entity.setSilent(true);
        entity.setGravity(false);
        entity.setInvulnerable(true);
        // 名前は MOB の customName ではなく、syncNameTag の TextDisplay を MOB に乗せて出す(統合版の人にだけ隠せるように)。
        // ラクダ・クリーパーだけは、上に乗せた名前を統合版が出さないので、syncNameTag で MOB に名前を付ける
        if (entity instanceof LivingEntity living) {
            living.setCollidable(false);
            living.setRemoveWhenFarAway(false);
            living.setCanPickupItems(false);
            living.setAI(false);
            living.setSilent(true);
        }
        if (entity instanceof Mob mob) {
            mob.setAware(false);
            mob.setSilent(true);
        }
        if (entity instanceof Sittable sittable) {
            sittable.setSitting(true);
        }
        if (entity instanceof Ageable ageable && kind.baby()) {
            ageable.setBaby();
            ageable.setAgeLock(true);
        }
        if (entity instanceof PufferFish puffer) {
            // 0 が通常、1 が半膨張、2 が最大。半膨張は別枠で、サイズはそのまま
            puffer.setPuffState(kind == CosmeticKinds.MountKind.PUFFERFISH_HALF ? 1 : 2);
        }
        if (entity instanceof Slime slime) {
            // サイズ 2 を縮めて、頭に乗るくらいの中くらいにする
            slime.setSize(2);
        }
        if (entity instanceof Pig pig) {
            pig.setSaddle(false);
        }
        if (entity instanceof Creeper creeper) {
            // 右クリックとダメージは別で止めている。半径 0 と着火解除で、漏れても壊さない
            creeper.setPowered(false);
            creeper.setIgnited(false);
            creeper.setExplosionRadius(0);
        }
        if (entity instanceof LivingEntity livingScaled) {
            double scale = kind.mountedScale();
            if (scale != 1.0) {
                AttributeInstance attribute = livingScaled.getAttribute(Attribute.SCALE);
                if (attribute != null) {
                    attribute.setBaseValue(scale);
                }
            }
        }
        if (entity instanceof Sniffer sniffer) {
            sniffer.setState(Sniffer.State.IDLING);
        }
        if (entity instanceof Wolf wolf) {
            wolf.setAngry(false);
        }
        if (entity instanceof Camel camel) {
            camel.setDashing(false);
        }
        if (entity instanceof Bee bee) {
            bee.setAnger(0);
            bee.setHasStung(false);
        }
        if (entity instanceof Chicken chicken) {
            chicken.setEggLayTime(Integer.MAX_VALUE);
        }
        if (entity instanceof Fox fox) {
            fox.setSleeping(false);
            fox.setDefending(false);
        }
        if (entity instanceof Villager villager) {
            villager.setProfession(Villager.Profession.NONE);
            villager.setVillagerExperience(0);
            villager.setAware(false);
        }
        if (entity instanceof PolarBear bear) {
            bear.setAI(false);
        }
        applyVariant(entity, kind, variant);
    }

    private void applyVariant(Entity entity, CosmeticKinds.MountKind kind, String stored) {
        switch (kind) {
            case CAT -> applyRegistry(Cat.Type.class, stored, type -> {
                if (entity instanceof Cat cat) {
                    cat.setCatType(type);
                }
            });
            case FROG -> applyRegistry(Frog.Variant.class, stored, variant -> {
                if (entity instanceof Frog frog) {
                    frog.setVariant(variant);
                }
            });
            case FOX -> {
                if (entity instanceof Fox fox) {
                    fox.setFoxType(pickEnum(Fox.Type.class, stored));
                }
            }
            case RABBIT -> {
                if (entity instanceof Rabbit rabbit) {
                    rabbit.setRabbitType(pickEnum(Rabbit.Type.class, stored));
                }
            }
            case PARROT -> {
                if (entity instanceof Parrot parrot) {
                    parrot.setVariant(pickEnum(Parrot.Variant.class, stored));
                }
            }
            case CHICKEN -> applyRegistry(Chicken.Variant.class, stored, variant -> {
                if (entity instanceof Chicken chicken) {
                    chicken.setVariant(variant);
                }
            });
            case COW -> applyRegistry(Cow.Variant.class, stored, variant -> {
                if (entity instanceof Cow cow) {
                    cow.setVariant(variant);
                }
            });
            case VILLAGER -> applyRegistry(Villager.Type.class, stored, type -> {
                if (entity instanceof Villager villager) {
                    villager.setVillagerType(type);
                }
            });
            case WOLF -> applyRegistry(Wolf.Variant.class, stored, variant -> {
                if (entity instanceof Wolf wolf) {
                    wolf.setVariant(variant);
                }
            });
            case AXOLOTL -> {
                if (entity instanceof Axolotl axolotl) {
                    axolotl.setVariant(pickEnum(Axolotl.Variant.class, stored));
                }
            }
            case SALMON -> {
                if (entity instanceof Salmon salmon) {
                    salmon.setVariant(pickEnum(Salmon.Variant.class, stored));
                }
            }
            case MOOSHROOM -> {
                if (entity instanceof MushroomCow cow) {
                    cow.setVariant(pickEnum(MushroomCow.Variant.class, stored));
                }
            }
            case ZOMBIE_NAUTILUS -> applyRegistry(ZombieNautilus.Variant.class, stored, variant -> {
                if (entity instanceof ZombieNautilus nautilus) {
                    nautilus.setVariant(variant);
                }
            });
            case PIG -> applyRegistry(Pig.Variant.class, stored, variant -> {
                if (entity instanceof Pig pig) {
                    pig.setVariant(variant);
                }
            });
            case SHEEP -> {
                if (entity instanceof Sheep sheep) {
                    sheep.setColor(pickEnum(DyeColor.class, stored));
                    sheep.setSheared(false);
                }
            }
            case GOAT -> {
                if (entity instanceof Goat goat) {
                    boolean screaming = stored == null || stored.isBlank()
                            ? ThreadLocalRandom.current().nextBoolean()
                            : "SCREAMING".equalsIgnoreCase(stored.trim());
                    goat.setScreaming(screaming);
                }
            }
            case PANDA -> {
                if (entity instanceof Panda panda) {
                    String mainRaw = stored;
                    String hiddenRaw = null;
                    if (stored != null && stored.contains("/")) {
                        String[] parts = stored.split("/", 2);
                        mainRaw = parts[0];
                        hiddenRaw = parts[1];
                    }
                    panda.setMainGene(pickEnum(Panda.Gene.class, mainRaw));
                    panda.setHiddenGene(pickEnum(Panda.Gene.class, hiddenRaw));
                    panda.setRolling(false);
                    panda.setOnBack(false);
                }
            }
            case PUFFERFISH, PUFFERFISH_HALF, BEE, POLAR_BEAR, TURTLE, COD, SQUID, GLOW_SQUID, ARMADILLO, NAUTILUS, SNIFFER, CAMEL, SLIME, CREEPER -> {
            }
        }
    }

    private String readVariant(LivingEntity entity) {
        if (entity instanceof Cat cat) {
            return keyOf(cat.getCatType());
        }
        if (entity instanceof Frog frog) {
            return keyOf(frog.getVariant());
        }
        if (entity instanceof Fox fox && fox.getFoxType() != null) {
            return fox.getFoxType().name();
        }
        if (entity instanceof Rabbit rabbit && rabbit.getRabbitType() != null) {
            return rabbit.getRabbitType().name();
        }
        if (entity instanceof Parrot parrot && parrot.getVariant() != null) {
            return parrot.getVariant().name();
        }
        if (entity instanceof Chicken chicken) {
            return keyOf(chicken.getVariant());
        }
        if (entity instanceof Cow cow) {
            return keyOf(cow.getVariant());
        }
        if (entity instanceof Villager villager) {
            return keyOf(villager.getVillagerType());
        }
        if (entity instanceof PufferFish puffer) {
            return Integer.toString(puffer.getPuffState());
        }
        if (entity instanceof Wolf wolf) {
            return keyOf(wolf.getVariant());
        }
        if (entity instanceof Axolotl axolotl && axolotl.getVariant() != null) {
            return axolotl.getVariant().name();
        }
        if (entity instanceof Salmon salmon && salmon.getVariant() != null) {
            return salmon.getVariant().name();
        }
        if (entity instanceof MushroomCow cow && cow.getVariant() != null) {
            return cow.getVariant().name();
        }
        if (entity instanceof ZombieNautilus nautilus) {
            return keyOf(nautilus.getVariant());
        }
        if (entity instanceof Goat goat) {
            return goat.isScreaming() ? "SCREAMING" : "NORMAL";
        }
        if (entity instanceof Pig pig) {
            return keyOf(pig.getVariant());
        }
        if (entity instanceof Sheep sheep && sheep.getColor() != null) {
            return sheep.getColor().name();
        }
        if (entity instanceof Panda panda && panda.getMainGene() != null && panda.getHiddenGene() != null) {
            return panda.getMainGene().name() + "/" + panda.getHiddenGene().name();
        }
        if (entity instanceof Slime slime) {
            return Integer.toString(slime.getSize());
        }
        return null;
    }

    private <T extends Enum<T>> T pickEnum(Class<T> type, String stored) {
        T[] values = type.getEnumConstants();
        if (stored != null && !stored.isBlank() && !"THE_KILLER_BUNNY".equalsIgnoreCase(stored.trim())) {
            try {
                return Enum.valueOf(type, stored.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // 保存値が壊れていれば引き直す
            }
        }
        List<T> options = new ArrayList<>();
        for (T value : values) {
            if (!"THE_KILLER_BUNNY".equals(value.name())) {
                options.add(value);
            }
        }
        if (options.isEmpty()) {
            return values[0];
        }
        return options.get(ThreadLocalRandom.current().nextInt(options.size()));
    }

    private <T extends Keyed> void applyRegistry(Class<T> type, String stored, Consumer<T> setter) {
        Registry<T> registry = Bukkit.getRegistry(type);
        if (registry == null) {
            return;
        }
        if (stored != null && !stored.isBlank()) {
            T value = registry.get(NamespacedKey.minecraft(stored));
            if (value != null) {
                setter.accept(value);
                return;
            }
        }
        List<T> all = new ArrayList<>();
        for (T value : registry) {
            all.add(value);
        }
        if (!all.isEmpty()) {
            setter.accept(all.get(ThreadLocalRandom.current().nextInt(all.size())));
        }
    }

    private static String keyOf(Keyed keyed) {
        if (keyed == null || keyed.getKey() == null) {
            return null;
        }
        return keyed.getKey().getKey();
    }

    private boolean hasLiveDisplay(Active state) {
        if (state.displays.isEmpty()) {
            return false;
        }
        for (BlockDisplay display : state.displays) {
            if (display == null || !display.isValid()) {
                return false;
            }
        }
        return true;
    }

    private void keepMounted(Player player, List<BlockDisplay> displays) {
        for (BlockDisplay display : displays) {
            if (display == null || !display.isValid() || player.getPassengers().contains(display)) {
                continue;
            }
            player.addPassenger(display);
        }
    }

    private void removeDisplays(Active state) {
        for (BlockDisplay display : state.displays) {
            if (display != null && display.isValid()) {
                removeQuietly(display);
            }
        }
        state.displays.clear();
        state.floorDisplays.clear();
        // 出し直すと元の高さに戻るので、座っていれば次の 3 tick で上げ直す
        state.floorLifted = false;
    }

    private void liftDisplays(Active state, float dy) {
        for (BlockDisplay display : state.floorDisplays) {
            if (display == null || !display.isValid()) {
                continue;
            }
            Transformation current = display.getTransformation();
            Vector3f translation = new Vector3f(current.getTranslation()).add(0.0f, dy, 0.0f);
            display.setTransformation(new Transformation(
                    translation, current.getLeftRotation(), current.getScale(), current.getRightRotation()));
        }
    }

    private void removeRider(Active state) {
        if (state.rider != null && state.rider.isValid()) {
            removeQuietly(state.rider);
        }
        state.rider = null;
    }

    /**
     * 何かが乗っているプレイヤーのネームタグは、Java 版のクライアントが表示しない。
     * 代わりに名前の TextDisplay を、頭上 MOB がいれば MOB の上に、ブロックだけならプレイヤーの頭の上に乗せる。
     * 透明な間(GSit の /lay など)はバニラと同じく出さない。/lay では GSit の寝姿に名前が出るので二重になる。
     */
    private void syncNameTag(Player player, Active state) {
        boolean hasRider = state.rider != null && state.rider.isValid();
        // ラクダ・クリーパーは、上に乗せた名前を統合版が出さない(2 段にしても出ない)。名前の TextDisplay をやめ、MOB そのものに名前を付ける
        boolean mobName = hasRider && usesMobName(state.rider);
        if (hasRider) {
            syncMobName(player, state.rider, mobName && !player.isInvisible());
        }
        boolean needed = (hasRider || !state.displays.isEmpty()) && !player.isInvisible() && !mobName;
        if (!needed) {
            removeNameTag(state);
            return;
        }
        // 鶏の上に直接乗せると、統合版は後から見始めた人に名前を出さない。文字のない TextDisplay を挟んで 2 段にする
        boolean onChicken = hasRider && state.rider instanceof Chicken;
        if (state.nameTag != null && state.nameTag.isValid() && state.nameTagOnChicken != onChicken) {
            // 鶏とそれ以外で高さが違うので作り直す
            removeNameTag(state);
        }
        Entity base;
        if (onChicken) {
            base = ensureSpacer(player, state);
        } else {
            removeSpacer(state);
            base = hasRider ? state.rider : player;
        }
        if (state.nameTag != null && state.nameTag.isValid()) {
            if (!base.getPassengers().contains(state.nameTag)) {
                // MOB が出た・消えたときは乗せ先を変える
                selfDismounting = true;
                try {
                    state.nameTag.leaveVehicle();
                } finally {
                    selfDismounting = false;
                }
                base.addPassenger(state.nameTag);
            }
            syncBedrockView(player, state);
            return;
        }
        if (state.nameTag != null) {
            removeQuietly(state.nameTag);
        }
        // 鶏のときは頭の高さに出す(足元に出すと、乗るまでの一瞬だけ床に名前が見える)
        Location spawnAt = onChicken ? state.rider.getLocation() : player.getLocation();
        spawnAt.setYaw(0.0f);
        spawnAt.setPitch(0.0f);
        TextDisplay nameTag = player.getWorld().spawn(spawnAt, TextDisplay.class, entity -> {
            tag(entity, player.getUniqueId());
            entity.text(player.displayName());
            entity.setPersistent(false);
            entity.setInvulnerable(true);
            entity.setGravity(false);
            // バニラのネームタグと同じく、常にこちらを向き、64 ブロックまで見える
            entity.setBillboard(Display.Billboard.CENTER);
            entity.setViewRange(1.0f);
            entity.setShadowRadius(0.0f);
            entity.setShadowStrength(0.0f);
            entity.setInterpolationDuration(0);
            entity.setTransformation(new Transformation(
                    new Vector3f(0.0f, onChicken ? CHICKEN_NAME_TAG_LIFT : NAME_TAG_LIFT, 0.0f),
                    new Quaternionf(),
                    new Vector3f(1.0f, 1.0f, 1.0f),
                    new Quaternionf()));
        });
        // バニラと同じく、自分の名前は自分には見せない
        player.hideEntity(plugin, nameTag);
        base.addPassenger(nameTag);
        state.nameTag = nameTag;
        state.nameTagOnChicken = onChicken;
        state.bedrockShown = null;
        syncBedrockView(player, state);
    }

    /**
     * 鶏の上に、名前を乗せるための文字のない TextDisplay を乗せる。
     * Geyser は、名前の乗り物が TextDisplay のときだけ名前の高さ(translation)を統合版の位置に使う。
     */
    private Entity ensureSpacer(Player player, Active state) {
        if (state.spacer != null && state.spacer.isValid() && state.rider.getPassengers().contains(state.spacer)) {
            return state.spacer;
        }
        removeSpacer(state);
        Location spawnAt = state.rider.getLocation();
        spawnAt.setYaw(0.0f);
        spawnAt.setPitch(0.0f);
        TextDisplay spacer = player.getWorld().spawn(spawnAt, TextDisplay.class, entity -> {
            tag(entity, player.getUniqueId());
            entity.text(Component.empty());
            entity.setPersistent(false);
            entity.setInvulnerable(true);
            entity.setGravity(false);
            entity.setShadowRadius(0.0f);
            entity.setShadowStrength(0.0f);
        });
        state.rider.addPassenger(spacer);
        state.spacer = spacer;
        return spacer;
    }

    private void removeSpacer(Active state) {
        if (state.spacer != null && state.spacer.isValid()) {
            removeQuietly(state.spacer);
        }
        state.spacer = null;
    }

    private static boolean usesMobName(Entity rider) {
        return rider instanceof Camel || rider instanceof Creeper;
    }

    /**
     * 頭上 MOB 自体の名前を、出すべきときだけ出す。Java 版は乗客のいない MOB の名前を出すので、こちらも 1 つになる。
     */
    private static void syncMobName(Player owner, LivingEntity rider, boolean show) {
        if (show) {
            if (rider.customName() == null || !rider.isCustomNameVisible()) {
                rider.customName(owner.displayName());
                rider.setCustomNameVisible(true);
            }
        } else if (rider.customName() != null) {
            rider.customName(null);
            rider.setCustomNameVisible(false);
        }
    }

    /**
     * 統合版は、頭上 MOB がいる人の本人の名前を、乗り物に乗っていれば隠し、立っていても見え始めて数秒後に隠す。
     * (Geyser は BlockDisplay を送らないので、ブロックだけの人は統合版から見ると乗客がいない。本人の名前が出る。)
     * そこで統合版の人には、頭上 MOB がいる間はこちらの名前を見せ、ブロックだけのときは隠して名前を 1 つにする。
     * 立っている人を見始めたばかりの統合版の人には、本人の名前が消えるまで onTrack で遅らせて見せる。
     * 見せる・隠すを切り替えるのは、値が変わったときだけ。
     */
    private void syncBedrockView(Player owner, Active state) {
        if (bedrock == null || state.nameTag == null || !state.nameTag.isValid()) {
            return;
        }
        boolean show = state.rider != null && state.rider.isValid();
        if (state.bedrockShown != null && state.bedrockShown == show) {
            return;
        }
        state.bedrockShown = show;
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer != owner && bedrock.is(viewer)) {
                if (show) {
                    viewer.showEntity(plugin, state.nameTag);
                } else {
                    viewer.hideEntity(plugin, state.nameTag);
                }
            }
        }
    }

    /**
     * 統合版の人がログインしたとき、隠す側の名前をその人から隠す(入ったばかりの人にはすべて見えているため)。
     */
    void onViewerJoin(Player viewer) {
        if (bedrock == null || !bedrock.is(viewer)) {
            return;
        }
        for (Map.Entry<UUID, Active> entry : active.entrySet()) {
            Active state = entry.getValue();
            if (entry.getKey().equals(viewer.getUniqueId()) || state.nameTag == null || !state.nameTag.isValid()
                    || Boolean.TRUE.equals(state.bedrockShown)) {
                continue;
            }
            viewer.hideEntity(plugin, state.nameTag);
        }
    }

    private void removeNameTag(Active state) {
        if (state.nameTag != null && state.nameTag.isValid()) {
            removeQuietly(state.nameTag);
        }
        state.nameTag = null;
        state.bedrockShown = null;
        removeSpacer(state);
    }

    private void stripPassengers(Player player) {
        for (Entity passenger : new ArrayList<>(player.getPassengers())) {
            if (isOurs(passenger)) {
                removeQuietly(passenger);
            }
        }
    }

    /**
     * 自前のエンティティを消す。消すときに出る降車イベントを、他のプラグインのテレポートと区別するため。
     */
    private void removeQuietly(Entity entity) {
        boolean previous = selfDismounting;
        selfDismounting = true;
        try {
            entity.remove();
        } finally {
            selfDismounting = previous;
        }
    }

    private void tag(Entity entity, UUID owner) {
        entity.getPersistentDataContainer().set(tagKey, PersistentDataType.BYTE, (byte) 1);
        entity.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, owner.toString());
    }

    private boolean isOurs(Entity entity) {
        Byte mark = entity.getPersistentDataContainer().get(tagKey, PersistentDataType.BYTE);
        return mark != null && mark == (byte) 1;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (isOurs(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTarget(EntityTargetEvent event) {
        if (isOurs(event.getEntity()) || (event.getTarget() != null && isOurs(event.getTarget()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (isOurs(event.getRightClicked())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucket(PlayerBucketEntityEvent event) {
        if (isOurs(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onShear(PlayerShearEntityEvent event) {
        if (isOurs(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPrime(ExplosionPrimeEvent event) {
        if (isOurs(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        if (event.getEntity() != null && isOurs(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCreeperPower(CreeperPowerEvent event) {
        if (isOurs(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRide(EntityMountEvent event) {
        // ラクダやオウムガイに他の人が乗るのは止める。名前の TextDisplay を MOB に乗せるのは自前同士なので通す
        if (isOurs(event.getMount()) && !isOurs(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSeat(EntityMountEvent event) {
        // 乗った情報はこのあと統合版に届き、その時点の頭上 MOB の有無で名前が決まるので、出し直しは要らない
        if (bedrock == null || !(event.getEntity() instanceof Player owner) || isOurs(event.getMount())) {
            return;
        }
        Active state = active.get(owner.getUniqueId());
        if (state != null) {
            state.seatedRider = state.rider != null && state.rider.isValid();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrack(PlayerTrackEntityEvent event) {
        // 統合版の人が、座っている人より後に頭上 MOB を見始めると、MOB が乗り物の情報より後に届いて本人の名前が出てしまう。
        // 次の tick にその人にだけ乗り物を出し直す。立っている人なら、こちらの名前を見せるのを遅らせる。
        // 乗客でないエンティティと、統合版でない人はすぐ抜ける
        if (bedrock == null || resending || !(event.getEntity().getVehicle() instanceof Player owner)) {
            return;
        }
        Active state = active.get(owner.getUniqueId());
        if (state == null || state.rider != event.getEntity() || !bedrock.is(event.getPlayer())) {
            return;
        }
        UUID viewerId = event.getPlayer().getUniqueId();
        UUID ownerId = owner.getUniqueId();
        if (!owner.isInsideVehicle()) {
            delayNameTag(event.getPlayer(), owner, state);
            return;
        }
        // 座ったまま MOB が出た直後(seatedRider が true になる前)は、全員への出し直しを tickSeated に任せる
        if (!Boolean.TRUE.equals(state.seatedRider)) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player viewer = Bukkit.getPlayer(viewerId);
            Player online = Bukkit.getPlayer(ownerId);
            if (viewer != null && online != null && online.isInsideVehicle() && viewer.canSee(online)) {
                resendTo(viewer, online);
            }
        });
    }

    /**
     * 統合版の人が、立っている人の頭上 MOB を見始めたとき(ログイン・接近)、統合版はしばらく本人の名前を出してから隠す。
     * その間にこちらの名前も見せると 2 つになるので、その人にだけこちらの名前を隠し、本人の名前が消えるころに見せる。
     * 出たばかりの MOB(付け替えなど)は、すでに見ている人なので遅らせない。
     */
    private void delayNameTag(Player viewer, Player owner, Active state) {
        TextDisplay nameTag = state.nameTag;
        if (nameTag == null || !nameTag.isValid() || state.rider.getTicksLived() < 20) {
            return;
        }
        viewer.hideEntity(plugin, nameTag);
        UUID viewerId = viewer.getUniqueId();
        UUID ownerId = owner.getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player online = Bukkit.getPlayer(viewerId);
            Active now = active.get(ownerId);
            // 待っている間に名前が作り直されていれば、作り直したときに見せ方を決め直しているので何もしない
            if (online == null || now == null || now.nameTag != nameTag || !nameTag.isValid()
                    || !Boolean.TRUE.equals(now.bedrockShown)) {
                return;
            }
            online.showEntity(plugin, nameTag);
        }, STANDING_NAME_DELAY_TICKS);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCombust(EntityCombustEvent event) {
        // ゾンビオウムガイなどのアンデッドが日光で燃えるので止める
        if (isOurs(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTransform(EntityTransformEvent event) {
        // 雷に打たれた豚がゾンビピグリンに、村人がウィッチに変わると、AI なし・無敵のまま自前の印のない MOB が残るので止める
        if (isOurs(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onZap(EntityZapEvent event) {
        // 雷での変身(豚・村人)は EntityTransformEvent とは別の HandlerList なので、ここでも止める
        if (isOurs(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDropItem(EntityDropItemEvent event) {
        // 頭の鶏が産む卵で、乗せている人のインベントリが埋まるので止める。鶏以外はすぐ抜ける
        if (event.getEntity().getType() == EntityType.CHICKEN && isOurs(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        // Paper は乗客がいる人の別ワールドへのテレポートを、PlayerTeleportEvent より前に失敗させる。
        // 乗客を降ろさずにテレポートするコマンド(teleport-commands)だけ、動く前に見た目を外す。見た目がない人はすぐ抜ける
        Player player = event.getPlayer();
        if (teleportCommands.isEmpty() || !active.containsKey(player.getUniqueId())) {
            return;
        }
        String message = normalizeCommand(event.getMessage());
        boolean matched = false;
        for (String command : teleportCommands) {
            if (message.equals(command) || message.startsWith(command + " ")) {
                matched = true;
                break;
            }
        }
        if (!matched) {
            return;
        }
        clear(player);
        UUID uuid = player.getUniqueId();
        pausedUntil.put(uuid, Bukkit.getCurrentTick() + COMMAND_PAUSE_TICKS);
        // 1 秒タスクを待たずに付け直す。続けてコマンドを打っていれば、最後のコマンドの予約に任せる
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Integer until = pausedUntil.get(uuid);
            if (until == null || until > Bukkit.getCurrentTick()) {
                return;
            }
            pausedUntil.remove(uuid);
            Player online = Bukkit.getPlayer(uuid);
            if (online != null) {
                refresh(online);
            }
        }, COMMAND_PAUSE_TICKS);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDismount(EntityDismountEvent event) {
        // Multiverse(passenger-mode: dismount_passengers)や EssentialsX は、乗客を降ろしてからテレポートする。
        // チャンクの生成を待つ間に 1 秒タスクが乗せ直すと失敗するので、テレポートが起きるまで(最長 10 秒)乗せ直さない。
        // 降車は止めない(止めると /tp や /home で飛べなくなる)。自分で外したときと、プレイヤー以外から降りたときはすぐ抜ける
        if (selfDismounting || !(event.getDismounted() instanceof Player player) || !isOurs(event.getEntity())) {
            return;
        }
        UUID uuid = player.getUniqueId();
        pausedUntil.put(uuid, Bukkit.getCurrentTick() + DISMOUNT_PAUSE_TICKS);
        // 降ろされた見た目はその場に残るので、次の tick にまとめて消す(イベントの中で消すと、また降車イベントが出る)。
        // 乗客ごとにイベントが出るので、予約は 1 人 1 回。付け直しはテレポート後の 1 秒タスクに任せる。
        // EssentialsX は降ろした直後の同じ tick にテレポートし、待ちが解除されているので、待ちの有無は見ない
        if (dismountClearPending.add(uuid)) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                dismountClearPending.remove(uuid);
                Player online = Bukkit.getPlayer(uuid);
                if (online != null) {
                    clear(online);
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        // 降ろされたあとのテレポートが済んだら、次の 1 秒タスクで付け直してよい(ゾーン外なら片付く)。見た目を止めていない人はすぐ抜ける
        if (!pausedUntil.isEmpty()) {
            pausedUntil.remove(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        clear(event.getEntity());
    }

    private static final class Active {
        private CosmeticKinds.ParticleKind particle;
        private CosmeticKinds.BlockKind block;
        private CosmeticKinds.MountKind mount;
        private final List<BlockDisplay> displays = new ArrayList<>();
        // 座っている間だけ上げる足元側。体から生えている分は含めない
        private final List<BlockDisplay> floorDisplays = new ArrayList<>();
        private LivingEntity rider;
        // 何かが乗っている間に出す、ネームタグの代わりの名前。頭上 MOB がいれば MOB に、いなければプレイヤーに乗せる
        private TextDisplay nameTag;
        // nameTag を鶏の上に 2 段で乗せる高さで作ったか
        private boolean nameTagOnChicken;
        // 鶏と名前の間に挟む、文字のない TextDisplay
        private TextDisplay spacer;
        // 統合版の人に名前を見せているか。null はまだ反映していない
        private Boolean bedrockShown;
        // 統合版が座ったと判定したときに頭上 MOB がいたか。null は座っていないか、まだわからない
        private Boolean seatedRider;
        // 足元のブロックを座面の高さに上げているか
        private boolean floorLifted;
    }

    private record Offset(float x, float y, float z, float sx, float sy, float sz,
            float pitch, float yaw, float roll, BlockFace[] faces, boolean floor) {
        private Offset(float x, float y, float z, float scale) {
            this(x, y, z, scale, scale, scale, 0f, 0f, 0f, null, false);
        }
    }

    static final class DebugGrant {
        String particle;
        String block;
        String mount;
        boolean particleNew;
        boolean blockNew;
        boolean mountNew;
        boolean inZone;
        boolean hidden;
        boolean concealed;
    }
}
