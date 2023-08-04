package gregtech.common.items.behaviors.monitorplugin;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.drawable.GuiDraw;
import com.cleanroommc.modularui.manager.GuiCreationContext;
import com.cleanroommc.modularui.network.NetworkUtils;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.theme.WidgetTheme;
import com.cleanroommc.modularui.utils.Color;
import com.cleanroommc.modularui.value.sync.GuiSyncManager;
import com.cleanroommc.modularui.value.sync.IntSyncValue;
import com.cleanroommc.modularui.value.sync.InteractionSyncHandler;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.Dialog;
import com.cleanroommc.modularui.widgets.ListWidget;
import com.cleanroommc.modularui.widgets.layout.Row;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;
import gregtech.api.capability.GregtechDataCodes;
import gregtech.api.gui.ModularUI;
import gregtech.api.gui.Widget;
import gregtech.api.gui.impl.FakeModularGui;
import gregtech.api.gui.widgets.SlotWidget;
import gregtech.api.items.behavior.MonitorPluginBaseBehavior;
import gregtech.api.items.behavior.ProxyHolderPluginBehavior;
import gregtech.api.items.toolitem.ToolClasses;
import gregtech.api.items.toolitem.ToolHelper;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.interfaces.IGregTechTileEntity;
import gregtech.api.metatileentity.multiblock.IMultiblockPart;
import gregtech.api.metatileentity.multiblock.MultiblockControllerBase;
import gregtech.api.newgui.GTGuis;
import gregtech.api.newgui.widgets.ProxyDisplayWidget;
import gregtech.api.pattern.PatternMatchContext;
import gregtech.api.util.FacingPos;
import gregtech.api.util.GTLog;
import gregtech.api.util.GregFakePlayer;
import gregtech.common.covers.CoverDigitalInterface;
import gregtech.common.gui.impl.FakeModularUIPluginContainer;
import gregtech.common.metatileentities.multi.electric.centralmonitor.MetaTileEntityCentralMonitor;
import gregtech.common.metatileentities.multi.electric.centralmonitor.MetaTileEntityMonitorScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraftforge.fml.common.ObfuscationReflectionHelper;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import net.minecraftforge.items.wrapper.PlayerMainInvWrapper;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.*;

public class FakeGuiPluginBehavior extends ProxyHolderPluginBehavior {

    private int partIndex;

    //run-time
    @SideOnly(Side.CLIENT)
    private FakeModularGui fakeModularGui;
    private BlockPos partPos;
    private FakeModularUIPluginContainer fakeModularUIContainer;
    private GregFakePlayer fakePlayer;
    private static final Method methodCreateUI = ObfuscationReflectionHelper.findMethod(MetaTileEntity.class, "createUI", ModularUI.class, EntityPlayer.class);

    static {
        methodCreateUI.setAccessible(true);
    }

    public void setConfig(int partIndex) {
        if (this.partIndex == partIndex || partIndex < 0) return;
        this.partIndex = partIndex;
        this.partPos = null;
        writePluginData(GregtechDataCodes.UPDATE_PLUGIN_CONFIG, buffer -> buffer.writeVarInt(this.partIndex));
        markAsDirty();
    }

    public MetaTileEntity getRealMTE() {
        MetaTileEntity target = this.holder.getMetaTileEntity();
        if (target instanceof MultiblockControllerBase && partIndex > 0) {
            if (partPos != null) {
                TileEntity entity = this.screen.getWorld().getTileEntity(partPos);
                if (entity instanceof IGregTechTileEntity) {
                    return ((IGregTechTileEntity) entity).getMetaTileEntity();
                } else {
                    partPos = null;
                    return null;
                }
            }
            PatternMatchContext context = ((MultiblockControllerBase) target).structurePattern.checkPatternFastAt(target.getWorld(), target.getPos(), target.getFrontFacing().getOpposite());
            if (context == null) {
                return null;
            }
            Set<IMultiblockPart> rawPartsSet = context.getOrCreate("MultiblockParts", HashSet::new);
            List<IMultiblockPart> parts = new ArrayList<>(rawPartsSet);
            parts.sort(Comparator.comparing((it) -> ((MetaTileEntity) it).getPos().hashCode()));
            if (parts.size() > partIndex - 1 && parts.get(partIndex - 1) instanceof MetaTileEntity) {
                target = (MetaTileEntity) parts.get(partIndex - 1);
                partPos = target.getPos();
            } else {
                return null;
            }
        }
        return target;
    }

    public void createFakeGui() {
        if (this.holder == null || this.screen == null || !this.screen.isValid()) return;
        try {
            fakePlayer = new GregFakePlayer(this.screen.getWorld());
            MetaTileEntity mte = getRealMTE();
            if (mte == null || (this.partIndex > 0 && this.holder.getMetaTileEntity() == mte)) {
                fakeModularUIContainer = null;
                if (this.screen.getWorld().isRemote) {
                    fakeModularGui = null;
                }
                return;
            }
            ModularUI ui = (ModularUI) methodCreateUI.invoke(mte, fakePlayer);
            if (ui == null) {
                fakeModularUIContainer = null;
                if (this.screen.getWorld().isRemote) {
                    fakeModularGui = null;
                }
                return;
            }
            List<Widget> widgets = new ArrayList<>();
            boolean hasPlayerInventory = false;
            for (Widget widget : ui.guiWidgets.values()) {
                if (widget instanceof SlotWidget) {
                    IInventory handler = ((SlotWidget) widget).getHandle().inventory;
                    if (handler instanceof PlayerMainInvWrapper || handler instanceof InventoryPlayer) {
                        hasPlayerInventory = true;
                        continue;
                    }
                }
                widgets.add(widget);
            }
            ModularUI.Builder builder = new ModularUI.Builder(ui.backgroundPath, ui.getWidth(), ui.getHeight() - (hasPlayerInventory ? 80 : 0));
            for (Widget widget : widgets) {
                builder.widget(widget);
            }
            ui = builder.build(ui.holder, ui.entityPlayer);
            fakeModularUIContainer = new FakeModularUIPluginContainer(ui, this);
            if (this.screen.getWorld().isRemote) {
                fakeModularGui = new FakeModularGui(ui, fakeModularUIContainer);
                writePluginAction(GregtechDataCodes.ACTION_PLUGIN_CONFIG, buffer -> {});
            }
        } catch (Exception e) {
            GTLog.logger.error(e);
        }
    }

    @Override
    public void readPluginAction(EntityPlayerMP player, int id, PacketBuffer buf) {
        if (id == GregtechDataCodes.ACTION_PLUGIN_CONFIG) {
            createFakeGui();
        }
        if (id == GregtechDataCodes.ACTION_FAKE_GUI) {
            if (this.fakeModularUIContainer != null) {
                fakeModularUIContainer.handleClientAction(buf);
            }
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound data) {
        super.writeToNBT(data);
        data.setInteger("part", partIndex);
    }

    @Override
    public void readFromNBT(NBTTagCompound data) {
        super.readFromNBT(data);
        partIndex = data.hasKey("part") ? data.getInteger("part") : 0;
    }

    @Override
    public void onHolderChanged(IGregTechTileEntity lastHolder) {
        if (holder == null) {
            if (this.screen.getWorld() != null && this.screen.getWorld().isRemote) {
                fakeModularGui = null;
            }
            fakeModularUIContainer = null;
            fakePlayer = null;
        } else {
            if (this.screen.getWorld().isRemote) {
                createFakeGui();
            }
        }
    }

    @Override
    public void update() {
        super.update();
        if (this.screen.getWorld().isRemote) {
            if (partIndex > 0 && fakeModularUIContainer == null && this.screen.getOffsetTimer() % 20 == 0) {
                createFakeGui();
            }
            if (fakeModularGui != null)
                fakeModularGui.updateScreen();
        } else {
            if (partIndex > 0 && this.screen.getOffsetTimer() % 20 == 0) {
                if (fakeModularUIContainer != null && getRealMTE() == null) {
                    this.writePluginData(GregtechDataCodes.UPDATE_PLUGIN_CONFIG, buf -> buf.writeVarInt(this.partIndex));
                    fakeModularUIContainer = null;
                }
            }
            if (fakeModularUIContainer != null)
                fakeModularUIContainer.detectAndSendChanges();
        }
    }

    @Override
    public MonitorPluginBaseBehavior createPlugin() {
        return new FakeGuiPluginBehavior();
    }

    @Override
    public void renderPlugin(float partialTicks, RayTraceResult rayTraceResult) {
        if (fakeModularGui != null) {
            double[] result = this.screen.checkLookingAt(rayTraceResult);
            GlStateManager.translate(0, 0, 0.01);
            if (result == null)
                fakeModularGui.drawScreen(0, 0, partialTicks);
            else
                fakeModularGui.drawScreen(result[0], result[1], partialTicks);
        }
    }

    @Override
    public boolean onClickLogic(EntityPlayer playerIn, EnumHand hand, EnumFacing facing, boolean isRight, double x, double y) {
        if (this.screen.getWorld().isRemote) return true;
        if (fakeModularUIContainer != null && fakeModularUIContainer.modularUI != null && !ToolHelper.isTool(playerIn.getHeldItemMainhand(), ToolClasses.SCREWDRIVER)) {
            int width = fakeModularUIContainer.modularUI.getWidth();
            int height = fakeModularUIContainer.modularUI.getHeight();
            float halfW = width / 2f;
            float halfH = height / 2f;
            float scale = 0.5f / Math.max(halfW, halfH);
            int mouseX = (int) ((x / scale) + (halfW > halfH ? 0 : (halfW - halfH)));
            int mouseY = (int) ((y / scale) + (halfH > halfW ? 0 : (halfH - halfW)));
            MetaTileEntity mte = getRealMTE();
            if (mte != null && 0 <= mouseX && mouseX <= width && 0 <= mouseY && mouseY <= height) {
                if (playerIn.isSneaking()) {
                    writePluginData(GregtechDataCodes.UPDATE_PLUGIN_CLICK, buf -> {
                        buf.writeVarInt(mouseX);
                        buf.writeVarInt(mouseY);
                        buf.writeVarInt(isRight ? 1 : 0);
                        buf.writeVarInt(fakeModularUIContainer.syncId);
                    });
                } else {
                    return isRight && mte.onRightClick(playerIn, hand, facing, null);
                }
            }
        }
        return false;
    }

    @Override
    public void readPluginData(int id, PacketBuffer buf) {
        if (id == GregtechDataCodes.UPDATE_PLUGIN_CONFIG) {
            this.partIndex = buf.readVarInt();
            this.partPos = null;
            createFakeGui();
        } else if (id == GregtechDataCodes.UPDATE_FAKE_GUI) {
            int windowID = buf.readVarInt();
            int widgetID = buf.readVarInt();
            if (fakeModularGui != null)
                fakeModularGui.handleWidgetUpdate(windowID, widgetID, buf);
        } else if (id == GregtechDataCodes.UPDATE_FAKE_GUI_DETECT) {
            if (fakeModularUIContainer != null)
                fakeModularUIContainer.handleSlotUpdate(buf);
        } else if (id == GregtechDataCodes.UPDATE_PLUGIN_CLICK) {
            int mouseX = buf.readVarInt();
            int mouseY = buf.readVarInt();
            int button = buf.readVarInt();
            int syncID = buf.readVarInt();
            if (fakeModularGui != null && fakeModularUIContainer != null) {
                fakeModularUIContainer.syncId = syncID;
                fakeModularGui.mouseClicked(mouseX, mouseY, button);
            }
        }
    }

    public static int mixColor(int c1, int c2) {
        return Color.interpolate(c1, c2, 0.5);
    }

    public static ProxyDisplayWidget makeProxyChooser(MetaTileEntityMonitorScreen screen, ModularPanel panel, GuiSyncManager syncManager) {
        ModularPanel chooser = new Dialog<>("proxy_chooser", null)
                .setCloseOnOutOfBoundsClick(true)
                .size(90, 100)
                .leftRel(1f).top(0)
                .relative(panel)
                .background((context1, x, y, width, height) -> {
                    WidgetTheme widgetTheme = panel.getContext().getTheme().getPanelTheme();
                    int color = widgetTheme.getColor();

                    GuiDraw.drawRect(x, y, width, height, mixColor(Color.WHITE.dark(7), color));
                    GuiDraw.drawBorder(x, y, width, height, mixColor(0xFF888888, color), 1);
                });

        ListWidget<?, ?, ?> proxiesWidget = new ListWidget<>();
        chooser.child(proxiesWidget.sizeRel(1f));
        ProxyDisplayWidget currentProxy = new ProxyDisplayWidget()
                .onMousePressed(mouseButton -> {
                    if (!panel.getScreen().isPanelOpen(chooser.getName())) {
                        panel.getScreen().openPanel(chooser);
                    }
                    return true;
                });
        int i = 0;
        for (FacingPos facingPos : ((MetaTileEntityCentralMonitor) screen.getController()).getAllCovers()) {
            CoverDigitalInterface digitalInterface = screen.getCoverFromPosSide(facingPos);
            if (digitalInterface != null) {
                ProxyDisplayWidget widget = ProxyDisplayWidget.make(digitalInterface);
                if (widget != null) {
                    if (facingPos.equals(screen.coverPos)) {
                        currentProxy.set(widget);
                    }
                    String key = GuiSyncManager.AUTO_SYNC_PREFIX + "proxy";
                    syncManager.syncValue(key, i, new InteractionSyncHandler()
                            .setOnMousePressed(mouseData -> {
                                if (mouseData.side.isClient()) {
                                    currentProxy.set(widget);
                                    chooser.animateClose();
                                } else {
                                    screen.setMode(widget.getFacingPos(), true);
                                }
                            }));
                    widget.syncHandler(key, i);
                    proxiesWidget.child(widget);
                    i++;
                }
            }
        }
        return currentProxy;
    }

    @Override
    public ModularPanel createPluginConfigUI(GuiSyncManager syncManager, @Nullable MetaTileEntityMonitorScreen screen, @Nullable GuiCreationContext context) {
        ModularPanel panel = GTGuis.createPanel("cm_plugin_fake_gui", 100, 95);
        panel.child(IKey.str("Plugin Config").asWidget().pos(5, 5));

        panel.child(makeProxyChooser(screen, panel, syncManager)
                        .pos(7, 18))
                .child(screen.makeDigitalModeWidget(syncManager)
                        .pos(7, 38));

        IntSyncValue partIndexValue = new IntSyncValue(() -> this.partIndex, val -> {
            this.partIndex = val;
            this.partPos = null;
            markAsDirty();
            if (NetworkUtils.isClient()) {
                createFakeGui();
            }
        });

        panel.child(IKey.str("Part: ").asWidget().pos(7, 58))
                .child(new Row()
                        .coverChildren()
                        .pos(7, 70)
                        .child(new ButtonWidget<>()
                                .overlay(IKey.str("-1"))
                                .onMousePressed(mouseButton -> {
                                    if (this.partIndex > 0) {
                                        partIndexValue.setIntValue(this.partIndex - 1);
                                    }
                                    return true;
                                }))
                        .child(new TextFieldWidget()
                                .size(50, 18)
                                .value(partIndexValue)
                                .setNumbers(0, Integer.MAX_VALUE))
                        .child(new ButtonWidget<>()
                                .overlay(IKey.str("+1"))
                                .onMousePressed(mouseButton -> {
                                    if (this.partIndex < Integer.MAX_VALUE) {
                                        partIndexValue.setIntValue(this.partIndex + 1);
                                    }
                                    return true;
                                })));

        return panel;
    }
}
