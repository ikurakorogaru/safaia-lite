package com.example.client;

import com.example.client.SafaiaClient;
import com.example.optimization.SafaiaCacheManager;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;

public final class SafaiaScreen
extends Screen {
    private static final int BACK = -15721424;
    private static final int PANEL = -15127738;
    private static final int LINE = -13612953;
    private static final int TEXT = -1379585;
    private static final int MUTED = -5850153;
    private static final int BLUE = -9257729;
    private static final int GREEN = -8857678;
    private final Screen parent;
    private int tab;
    private int left;
    private int top;
    private int panelWidth;
    private int panelHeight;
    private int body;
    private int limit = 20;
    private int ticks;
    private String notice = "操作を選んでください。";
    private String xValue = "";
    private String zValue = "";
    private EditBox xBox;
    private EditBox zBox;
    private final ChunkSurvey scan = new ChunkSurvey();
    private final ChunkSurvey.Probe chunkProbe = (x, z) -> this.minecraft.level != null &&
        this.minecraft.level.getChunkSource().getChunk(x, z,
            net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false) != null;
    private String fpsText = "0", memoryPercent = "0%", pingText = "—";
    private String countsText = "", memoryText = "", cacheText = "";
    private String mapCountsText = "調査中…", mapCenterText = "";
    private int fps;
    private int ping;
    private int cacheCount;
    private int chunks;
    private long used;
    private long max;

    public SafaiaScreen(Screen parent) {
        super(Component.literal("Safaia — パフォーマンス"));
        this.parent = parent;
    }

    public boolean isPauseScreen() {
        return false;
    }

    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }

    protected void init() {
        this.panelWidth = Math.min(560, this.width - 16);
        this.panelHeight = Math.min(300, this.height - 16);
        this.left = (this.width - this.panelWidth) / 2;
        this.top = (this.height - this.panelHeight) / 2;
        this.body = this.top + 70;
        this.xBox = null;
        this.zBox = null;
        int tabWidth = (this.panelWidth - 24) / 3;
        String[] tabs = new String[]{"状態", "メモリ", "チャンク"};
        for (int i = 0; i < 3; ++i) {
            int selected = i;
            FlatButton button = this.button(this.left + 12 + i * tabWidth, this.top + 38, tabWidth - 4, tabs[i], () -> {
                this.tab = selected;
                this.notice = "操作を選んでください。";
                this.rebuildWidgets();
            });
            button.selected = this.tab == i;
        }
        this.button(this.left + this.panelWidth - 72, this.top + this.panelHeight - 29, 60, "閉じる", this::onClose);
        if (this.tab == 0) {
            this.button(this.left + 12, this.body + 72, this.panelWidth - 24, "自動キャッシュ整理: " + (SafaiaClient.isOptimizationEnabled() ? "ON" : "OFF"), () -> {
                this.notice = SafaiaClient.setOptimization(!SafaiaClient.isOptimizationEnabled());
                this.rebuildWidgets();
            }).setTooltip(Tooltip.create(Component.literal("ONにすると、1分ごとに5分以上未使用のSafaiaキャッシュを整理します。設定は保存されます。")));
        } else if (this.tab == 1) {
            int w = (this.panelWidth - 28) / 2;
            this.button(this.left + 12, this.body + 90, w, "期限切れを整理", () -> this.run(SafaiaClient::cleanup));
            this.button(this.left + 16 + w, this.body + 90, w, "キャッシュを全削除", () -> this.run(SafaiaClient::clearCache)).setTooltip(Tooltip.create(Component.literal("Safaiaの一時キャッシュだけを削除します。ワールドやファイルは削除しません。")));
        } else {
            int controlsX = this.left + 132;
            int controlsW = this.panelWidth - 144;
            this.button(controlsX, this.body + 17, controlsW, "解放上限: " + this.limit + " 個", () -> {
                this.limit = switch (this.limit) {
                    case 10 -> 20;
                    case 20 -> 50;
                    case 50 -> 100;
                    default -> 10;
                };
                this.rebuildWidgets();
            });
            FlatButton unload = this.button(controlsX, this.body + 42, controlsW, "遠方を解放…", () -> this.confirm(() -> SafaiaClient.unloadFarChunks(this.limit)));
            unload.active = SafaiaClient.inWorld();
            int fieldW = (controlsW - 6) / 2;
            this.xBox = this.addRenderableWidget(new EditBox(this.font, controlsX, this.body + 72, fieldW, 18, Component.literal("チャンクX座標")));
            this.zBox = this.addRenderableWidget(new EditBox(this.font, controlsX + fieldW + 6, this.body + 72, fieldW, 18, Component.literal("チャンクZ座標")));
            this.xBox.setMaxLength(11);
            this.zBox.setMaxLength(11);
            this.xBox.setHint(Component.literal("チャンクX"));
            this.zBox.setHint(Component.literal("チャンクZ"));
            this.xBox.setValue(this.xValue);
            this.zBox.setValue(this.zValue);
            FlatButton drop = this.button(controlsX, this.body + 95, controlsW, "指定座標を解放…", () -> {
                try {
                    int x = Integer.parseInt(this.xBox.getValue());
                    int z = Integer.parseInt(this.zBox.getValue());
                    this.confirm(() -> SafaiaClient.dropChunk(x, z));
                } catch (NumberFormatException e) {
                    this.notice = "XとZに整数を入力してください。";
                }
            });
            Runnable validate = () -> {
                this.xValue = this.xBox.getValue();
                this.zValue = this.zBox.getValue();
                try {
                    Integer.parseInt(this.xValue);
                    Integer.parseInt(this.zValue);
                    drop.active = SafaiaClient.inWorld();
                } catch (NumberFormatException e) {
                    drop.active = false;
                }
            };
            this.xBox.setResponder(s -> validate.run());
            this.zBox.setResponder(s -> validate.run());
            validate.run();
        }
        if (this.tab == 0) {
            int presetWidth = (this.panelWidth - 28) / 2;
            this.button(this.left + 12, this.body + 97, presetWidth, "軽量プリセットを適用", () -> this.run(PerformancePreset::apply))
                .setTooltip(Tooltip.create(Component.literal("描画距離を最大8、シミュレーション距離を最大5に制限。雲・影・滑らかな陰影をOFF、パーティクル最少。元の設定を保存します。")));
            this.button(this.left + 16 + presetWidth, this.body + 97, presetWidth, "画質設定を元に戻す", () -> this.run(PerformancePreset::restore));
        }
        this.refresh();
    }

    private void run(Supplier<String> action) {
        this.notice = action.get();
        this.scan.invalidate();
        this.refresh();
    }

    private void confirm(Supplier<String> action) {
        ClientLevel expectedLevel = this.minecraft.level;
        this.minecraft.gui.setScreen(new ConfirmScreen(yes -> {
            if (yes) {
                this.notice = this.minecraft.level == expectedLevel && expectedLevel != null ? action.get() : "ワールドが変わったため中止しました。";
            }
            this.scan.invalidate();
            this.minecraft.gui.setScreen(this);
        }, Component.literal("チャンクを解放しますか？"), Component.literal("地形が一時的に消え、再接続が必要になる場合があります。ワールドの保存データは削除しません。現在地から8チャンク以内は保護します。"), Component.literal("解放する"), Component.literal("キャンセル")));
    }

    public void tick() {
        if (this.tab == 2) {
            if (SafaiaClient.inWorld()) {
                net.minecraft.world.level.ChunkPos pos = this.minecraft.player.chunkPosition();
                if (this.scan.update(this.minecraft.level, pos.x(), pos.z(),
                        this.minecraft.options.getEffectiveRenderDistance(), this.chunkProbe)) {
                    this.mapCountsText = "周辺 " + scan.loaded() + " / 遠方 " + (scan.loaded() - scan.near());
                    this.mapCenterText = "中心 " + scan.centerX() + ", " + scan.centerZ() + "・半径 " + scan.radius() + "・約2秒周期";
                }
            } else {
                this.scan.update(null, 0, 0, 0, this.chunkProbe);
            }
        }

        if (++this.ticks >= 20) {
            this.ticks = 0;
            this.refresh();
        }
    }

    private void refresh() {
        this.fps = this.minecraft.getFps();
        this.ping = SafaiaClient.ping();
        this.used = SafaiaClient.usedMemory();
        this.max = SafaiaClient.maxMemory();
        this.cacheCount = SafaiaCacheManager.getCacheCount();
        this.chunks = this.minecraft.level == null ? 0 : this.minecraft.level.getChunkSource().getLoadedChunksCount();
        this.fpsText = Integer.toString(this.fps);
        this.memoryPercent = (this.max == 0 ? 0 : this.used * 100 / this.max) + "%";
        this.pingText = this.ping < 0 ? "—" : this.ping + " ms";
        this.countsText = "読込済みチャンク: " + this.chunks + " / キャッシュ: " + this.cacheCount;
        this.memoryText = "使用中  " + this.used + " / " + this.max + " MB";
        this.cacheText = "Safaiaキャッシュ: " + this.cacheCount + " 件（上限256件）";
    }

    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) {
        g.fill(0, 0, this.width, this.height, -637006051);
        g.fill(this.left, this.top, this.left + this.panelWidth, this.top + this.panelHeight, -15721424);
        g.outline(this.left, this.top, this.panelWidth, this.panelHeight, -13612953);
        g.fill(this.left, this.top, this.left + 3, this.top + 29, -9257729);
    }

    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        super.extractRenderState(g, mx, my, delta);
        this.text(g, "SAFAIA", this.left + 14, this.top + 13, -1379585);
        this.text(g, "パフォーマンス", this.left + 66, this.top + 13, -5850153);
        g.fill(this.left + 12, this.top + this.panelHeight - 36, this.left + this.panelWidth - 12, this.top + this.panelHeight - 35, -13612953);
        this.text(g, this.font.plainSubstrByWidth(this.notice, this.panelWidth - 98), this.left + 12, this.top + this.panelHeight - 23, -5850153);
        if (this.tab == 0) {
            this.drawOverview(g);
        } else if (this.tab == 1) {
            this.drawMemory(g);
        } else {
            this.drawChunks(g);
        }
    }

    private void drawOverview(GuiGraphicsExtractor g) {
        int w = (this.panelWidth - 32) / 3;
        this.stat(g, this.left + 12, this.body, w, "FPS", this.fpsText, this.fps >= 60 ? -8857678 : -9257729);
        this.stat(g, this.left + 16 + w, this.body, w, "メモリ", this.memoryPercent, -9257729);
        this.stat(g, this.left + 20 + w * 2, this.body, w, "Ping", this.pingText, -1379585);
        this.text(g, this.countsText, this.left + 12, this.body + 56, -1379585);
        if (this.panelHeight >= 260) {
            this.text(g, "F8で開く・Tabで移動・Enterで実行・Escで閉じる", this.left + 12, this.body + 132, -5850153);
        }
    }

    private void drawMemory(GuiGraphicsExtractor g) {
        this.text(g, this.memoryText, this.left + 12, this.body + 5, -1379585);
        int barWidth = this.panelWidth - 24;
        g.fill(this.left + 12, this.body + 24, this.left + 12 + barWidth, this.body + 32, -13612953);
        g.fill(this.left + 12, this.body + 24, this.left + 12 + (int)((double)barWidth * Math.min(1.0, (double)this.used / (double)Math.max(1L, this.max))), this.body + 32, -9257729);
        this.text(g, this.cacheText, this.left + 12, this.body + 45, -1379585);
        this.text(g, "5分以上使っていないキャッシュが整理対象です。", this.left + 12, this.body + 62, -5850153);
        if (this.panelHeight >= 260) {
            this.text(g, "強制GCは行いません。0件なら整理は不要です。", this.left + 12, this.body + 132, -5850153);
        }
    }

    private void drawChunks(GuiGraphicsExtractor g) {
        int mapX = this.left + 12;
        int mapY = this.body + 3;
        if (!SafaiaClient.inWorld() || !this.scan.ready()) {
            this.text(g, SafaiaClient.inWorld() ? "調査中…" : "ワールド未接続", mapX, mapY + 36, -5850153);
            return;
        }
        int side = this.scan.radius() * 2 + 1;
        int cell = Math.max(1, 99 / side);
        int mapSize = cell * side;
        g.fill(mapX - 1, mapY - 1, mapX + mapSize + 1, mapY + mapSize + 1, -13612953);
        for (int z = 0; z < side; ++z) {
            for (int x = 0; x < side; ++x) {
                int color;
                int dx = x - this.scan.radius();
                int dz = z - this.scan.radius();
                color = this.scan.cells()[z * side + x] ? (dx * dx + dz * dz <= 64 ? -9257729 : -8857678) : -15721424;
                if (dx == 0 && dz == 0) {
                    color = -1;
                }
                g.fill(mapX + x * cell, mapY + z * cell, mapX + (x + 1) * cell - (cell > 2 ? 1 : 0), mapY + (z + 1) * cell - (cell > 2 ? 1 : 0), color);
            }
        }
        this.text(g, "青: 保護 / 緑: 遠方", mapX, this.body + 106, -5850153);
        this.text(g, this.mapCountsText, this.left + 132, this.body + 3, -1379585);
        if (this.panelHeight >= 260) {
            this.text(g, this.mapCenterText, this.left + 12, this.body + 132, -5850153);
        }
    }

    private void stat(GuiGraphicsExtractor g, int x, int y, int w, String label, String value, int color) {
        g.fill(x, y, x + w, y + 43, -15127738);
        this.text(g, label, x + 8, y + 7, -5850153);
        this.text(g, value, x + 8, y + 25, color);
    }

    private void text(GuiGraphicsExtractor g, String value, int x, int y, int color) {
        g.text(this.font, value, x, y, color, false);
    }

    private FlatButton button(int x, int y, int w, String label, Runnable action) {
        return this.addRenderableWidget(new FlatButton(x, y, w, label, action));
    }

    private static final class FlatButton
    extends Button {
        private boolean selected;
        private Component cachedMessage;
        private String cachedLabel;
        private int cachedWidth = -1;
        private int cachedTextWidth;

        FlatButton(int x, int y, int w, String label, Runnable action) {
            super(x, y, w, 20, Component.literal(label), b -> action.run(), DEFAULT_NARRATION);
        }

        protected void extractContents(GuiGraphicsExtractor g, int mx, int my, float delta) {
            int color = !this.active ? -15721424 : (this.isHoveredOrFocused() ? -13214585 : (this.selected ? -14332808 : -15127738));
            g.fill(this.getX(), this.getY(), this.getX() + this.getWidth(), this.getY() + this.getHeight(), color);
            g.outline(this.getX(), this.getY(), this.getWidth(), this.getHeight(), this.isFocused() ? -1379585 : (this.selected ? -9257729 : -13612953));
            if (this.selected) {
                g.fill(this.getX() + 1, this.getY() + this.getHeight() - 2, this.getX() + this.getWidth() - 1, this.getY() + this.getHeight() - 1, -9257729);
            }
            Font f = Minecraft.getInstance().font;
            if (cachedMessage != this.getMessage() || cachedWidth != this.getWidth()) {
                cachedMessage = this.getMessage();
                cachedWidth = this.getWidth();
                cachedLabel = f.plainSubstrByWidth(cachedMessage.getString(), cachedWidth - 8);
                cachedTextWidth = f.width(cachedLabel);
            }
            g.text(f, cachedLabel, this.getX() + (this.getWidth() - cachedTextWidth) / 2, this.getY() + 6, this.active ? -1379585 : -5850153, false);
        }
    }
}

