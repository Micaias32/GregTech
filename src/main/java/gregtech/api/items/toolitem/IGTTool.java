package gregtech.api.items.toolitem;

import appeng.api.implementations.items.IAEWrench;
import buildcraft.api.tools.IToolWrench;
import cofh.api.item.IToolHammer;
import com.enderio.core.common.interfaces.IOverlayRenderAware;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import crazypants.enderio.api.tool.ITool;
import forestry.api.arboriculture.IToolGrafter;
import gregtech.api.GTValues;
import gregtech.api.GregTechAPI;
import gregtech.api.capability.GregtechCapabilities;
import gregtech.api.capability.IElectricItem;
import gregtech.api.capability.impl.ElectricItem;
import gregtech.api.gui.GuiTextures;
import gregtech.api.gui.ModularUI;
import gregtech.api.gui.widgets.ClickButtonWidget;
import gregtech.api.gui.widgets.DynamicLabelWidget;
import gregtech.api.items.gui.ItemUIFactory;
import gregtech.api.items.gui.PlayerInventoryHolder;
import gregtech.api.items.metaitem.ElectricStats;
import gregtech.api.unification.OreDictUnifier;
import gregtech.api.unification.material.Material;
import gregtech.api.unification.material.Materials;
import gregtech.api.unification.material.properties.DustProperty;
import gregtech.api.unification.material.properties.PropertyKey;
import gregtech.api.unification.material.properties.ToolProperty;
import gregtech.api.unification.stack.MaterialStack;
import gregtech.api.util.GTLog;
import gregtech.common.ConfigHolder;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLog;
import net.minecraft.block.BlockWeb;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.enchantment.EnchantmentDurability;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Enchantments;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.stats.StatList;
import net.minecraft.util.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.fml.common.Optional;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.apache.logging.log4j.ThreadContext;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import static gregtech.api.items.armor.IArmorLogic.*;

/**
 * Backing of every variation of a GT Tool
 */
@Optional.InterfaceList({
        @Optional.Interface(modid = GTValues.MODID_APPENG, iface = "appeng.api.implementations.items.IAEWrench"),
        @Optional.Interface(modid = GTValues.MODID_BC, iface = "buildcraft.api.tools.IToolWrench"),
        @Optional.Interface(modid = GTValues.MODID_COFH, iface = "cofh.api.item.IToolHammer"),
        @Optional.Interface(modid = GTValues.MODID_EIO, iface = "crazypants.enderio.api.tool.ITool"),
        @Optional.Interface(modid = GTValues.MODID_FR, iface = "forestry.api.arboriculture.IToolGrafter"),
        @Optional.Interface(modid = GTValues.MODID_EIO, iface = "com.enderio.core.common.interfaces.IOverlayRenderAware")})
public interface IGTTool extends ItemUIFactory, IAEWrench, IToolWrench, IToolHammer, ITool, IToolGrafter, IOverlayRenderAware {

    String getDomain();

    String getId();

    boolean isElectric();

    int getElectricTier();

    IGTToolDefinition getToolStats();

    @Nullable
    SoundEvent getSound();

    Set<Block> getEffectiveBlocks();

    Set<String> getOreDictNames();

    default Item get() {
        return (Item) this;
    }

    default ItemStack get(Material material) {
        ItemStack stack = new ItemStack(get());
        NBTTagCompound toolTag = getToolTag(stack);
        // Set Material
        toolTag.setString("Material", material.toString());
        // Set other tool stats (durability)
        ToolProperty toolProperty = material.getProperty(PropertyKey.TOOL);
        toolTag.setInteger("MaxDurability", toolProperty.getToolDurability());
        toolTag.setInteger("Durability", 0);
        // Set material enchantments
        EnchantmentHelper.setEnchantments(toolProperty.getEnchantments(), stack);
        // Set AoEDefinition
        AoEDefinition aoeDefinition = getToolStats().getAoEDefinition(stack);
        toolTag.setInteger("AoEMaxColumn", aoeDefinition.column);
        toolTag.setInteger("AoEMaxRow", aoeDefinition.row);
        toolTag.setInteger("AoEMaxLayer", aoeDefinition.layer);
        toolTag.setInteger("AoEColumn", aoeDefinition.column);
        toolTag.setInteger("AoERow", aoeDefinition.row);
        toolTag.setInteger("AoELayer", aoeDefinition.layer);
        // Set behaviours
        NBTTagCompound behaviourTag = getBehaviourTag(stack);
        Set<String> toolClasses = stack.getItem().getToolClasses(stack);
        behaviourTag.setBoolean("SilkHarvestIce", toolClasses.contains("saw"));
        behaviourTag.setBoolean("TorchPlacing", true);
        behaviourTag.setBoolean("TreeFelling", toolClasses.contains("axe"));
        behaviourTag.setBoolean("RelocateMinedBlocks", false);
        return stack;
    }

    default ItemStack get(Material material, long defaultCharge, long defaultMaxCharge) {
        ItemStack stack = get(material);
        if (isElectric()) {
            ElectricItem electricItem = (ElectricItem) stack.getCapability(GregtechCapabilities.CAPABILITY_ELECTRIC_ITEM, null);
            if (electricItem != null) {
                electricItem.setMaxChargeOverride(defaultMaxCharge);
                electricItem.setCharge(defaultCharge);
            }
        }
        return stack;
    }

    default ItemStack get(Material material, long defaultMaxCharge) {
        return get(material, defaultMaxCharge, defaultMaxCharge);
    }

    default NBTTagCompound getToolTag(ItemStack stack) {
        return stack.getOrCreateSubCompound("GT.Tools");
    }

    default NBTTagCompound getBehaviourTag(ItemStack stack) {
        return stack.getOrCreateSubCompound("GT.Behaviours");
    }

    default Material getToolMaterial(ItemStack stack) {
        NBTTagCompound toolTag = getToolTag(stack);
        String string = toolTag.getString("Material");
        Material material = GregTechAPI.MaterialRegistry.get(string);
        if (material == null) {
            GTLog.logger.error("Attempt to get {} as a tool material, but material does not exist. Using Neutronium instead.", string);
            toolTag.setString("Material", (material = Materials.Neutronium).toString());
        }
        return material;
    }

    default ToolProperty getToolProperty(ItemStack stack) {
        Material material = getToolMaterial(stack);
        ToolProperty property = material.getProperty(PropertyKey.TOOL);
        if (property == null) {
            GTLog.logger.error("Tool property for {} does not exist. Using Neutronium's tool property instead.", material);
            property = Materials.Neutronium.getProperty(PropertyKey.TOOL);
        }
        return property;
    }

    default DustProperty getDustProperty(ItemStack stack) {
        Material material = getToolMaterial(stack);
        DustProperty property = material.getProperty(PropertyKey.DUST);
        if (property == null) {
            GTLog.logger.error("Dust property for {} does not exist. Using Neutronium's dust property instead.", material);
            property = Materials.Neutronium.getProperty(PropertyKey.DUST);
        }
        return property;
    }

    default float getMaterialToolSpeed(ItemStack stack) {
        return getToolProperty(stack).getToolSpeed();
    }

    default float getMaterialAttackDamage(ItemStack stack) {
        return getToolProperty(stack).getToolAttackDamage();
    }

    default int getMaterialDurability(ItemStack stack) {
        return getToolProperty(stack).getToolDurability();
    }

    default int getMaterialEnchantability(ItemStack stack) {
        return getToolProperty(stack).getToolEnchantability();
    }

    default int getMaterialHarvestLevel(ItemStack stack) {
        return getDustProperty(stack).getHarvestLevel();
    }

    default long getMaxCharge(ItemStack stack) {
        if (isElectric()) {
            NBTTagCompound tag = stack.getTagCompound();
            if (tag != null && tag.hasKey("MaxCharge", Constants.NBT.TAG_LONG)) {
                return stack.getTagCompound().getLong("MaxCharge");
            }
        }
        return -1L;
    }

    default long getCharge(ItemStack stack) {
        if (isElectric()) {
            NBTTagCompound tag = stack.getTagCompound();
            if (tag != null && tag.hasKey("Charge", Constants.NBT.TAG_LONG)) {
                return stack.getTagCompound().getLong("Charge");
            }
        }
        return -1L;
    }

    default float getTotalToolSpeed(ItemStack stack) {
        NBTTagCompound toolTag = getToolTag(stack);
        if (toolTag.hasKey("ToolSpeed", Constants.NBT.TAG_FLOAT)) {
            return toolTag.getFloat("ToolSpeed");
        }
        float toolSpeed = getMaterialToolSpeed(stack) + getToolStats().getBaseEfficiency(stack);
        toolTag.setFloat("ToolSpeed", toolSpeed);
        return toolSpeed;
    }

    default float getTotalAttackDamage(ItemStack stack) {
        NBTTagCompound toolTag = getToolTag(stack);
        if (toolTag.hasKey("AttackDamage", Constants.NBT.TAG_FLOAT)) {
            return toolTag.getFloat("AttackDamage");
        }
        float attackDamage = getMaterialAttackDamage(stack) + getToolStats().getBaseDamage(stack);
        toolTag.setFloat("AttackDamage", attackDamage);
        return attackDamage;
    }

    default int getTotalMaxDurability(ItemStack stack) {
        NBTTagCompound toolTag = getToolTag(stack);
        if (toolTag.hasKey("MaxDurability", Constants.NBT.TAG_INT)) {
            return toolTag.getInteger("MaxDurability");
        }
        int maxDurability = getMaterialDurability(stack) + getToolStats().getBaseDurability(stack);
        toolTag.setInteger("MaxDurability", maxDurability);
        return maxDurability;
    }

    default int getTotalEnchantability(ItemStack stack) {
        NBTTagCompound toolTag = getToolTag(stack);
        if (toolTag.hasKey("Enchantability", Constants.NBT.TAG_INT)) {
            return toolTag.getInteger("Enchantability");
        }
        int enchantability = getMaterialEnchantability(stack);
        toolTag.setInteger("Enchantability", enchantability);
        return enchantability;
    }

    default int getTotalHarvestLevel(ItemStack stack) {
        NBTTagCompound toolTag = getToolTag(stack);
        if (toolTag.hasKey("HarvestLevel", Constants.NBT.TAG_INT)) {
            return toolTag.getInteger("HarvestLevel");
        }
        int harvestLevel = getMaterialHarvestLevel(stack) + getToolStats().getBaseQuality(stack);
        toolTag.setInteger("HarvestLevel", harvestLevel);
        return harvestLevel;
    }

    default AoEDefinition getMaxAoEDefinition(ItemStack stack) {
        return AoEDefinition.readMax(getToolTag(stack));
    }

    default AoEDefinition getAoEDefinition(ItemStack stack) {
        return AoEDefinition.read(getToolTag(stack), getMaxAoEDefinition(stack));
    }

    @SideOnly(Side.CLIENT)
    default int getColor(ItemStack stack, int tintIndex) {
        return tintIndex % 2 == 1 ? getToolMaterial(stack).getMaterialRGB() : 0xFFFFFF;
    }

    // Item.class methods
    default float definition$getDestroySpeed(ItemStack stack, IBlockState state) {
        for (String type : get().getToolClasses(stack)) {
            if (type.equals("sword")) {
                Block block = state.getBlock();
                if (block instanceof BlockWeb) {
                    return 15F;
                } else if (getEffectiveBlocks().contains(block)) {
                    return getTotalToolSpeed(stack);
                } else {
                    net.minecraft.block.material.Material material = state.getMaterial();
                    return material != net.minecraft.block.material.Material.PLANTS && material != net.minecraft.block.material.Material.VINE && material != net.minecraft.block.material.Material.CORAL && material != net.minecraft.block.material.Material.LEAVES && material != net.minecraft.block.material.Material.GOURD ? 1.0F : 1.5F;
                }
            } else if (state.getBlock().isToolEffective(type, state)) {
                return getTotalToolSpeed(stack);
            }
        }
        return getEffectiveBlocks().contains(state.getBlock()) ? getTotalToolSpeed(stack) : 1.0F;
    }

    default boolean definition$hitEntity(ItemStack stack, EntityLivingBase target, EntityLivingBase attacker) {
        damageItem(stack, attacker, getToolStats().getToolDamagePerAttack(stack));
        return true;
    }

    default boolean definition$onBlockDestroyed(ItemStack stack, World worldIn, IBlockState state, BlockPos pos, EntityLivingBase entityLiving) {
        if (!worldIn.isRemote) {
            if (!entityLiving.isSneaking()) {
                EntityPlayerMP serverPlayer = (EntityPlayerMP) entityLiving;
                if (getBehaviourTag(stack).getBoolean("TreeFelling") && !ThreadContext.containsKey("GT_TreeFelling")) {
                    ThreadContext.put("GT_TreeFelling", "");
                    if (!treeLogging(worldIn, serverPlayer, stack, pos)) {
                        if ((double) state.getBlockHardness(worldIn, pos) != 0.0D) {
                            damageItem(stack, entityLiving, getToolStats().getToolDamagePerBlockBreak(stack));
                            return true;
                        }
                    }
                    ThreadContext.remove("GT_TreeFelling");
                    return true;
                } else if (!ThreadContext.containsKey("GT_AoE_Breaking")) {
                    ThreadContext.put("GT_AoE_Breaking", "");
                    for (BlockPos aoePos : getHarvestableBlocks(stack, worldIn, serverPlayer)) {
                        serverPlayer.interactionManager.tryHarvestBlock(aoePos);
                        if (stack.isEmpty()) {
                            ThreadContext.remove("GT_AoE_Breaking");
                            return true;
                        }
                    }
                    ThreadContext.remove("GT_AoE_Breaking");
                }
            }
            if ((double) state.getBlockHardness(worldIn, pos) != 0.0D) {
                damageItem(stack, entityLiving, getToolStats().getToolDamagePerBlockBreak(stack));
            }
        }
        return true;
    }

    default boolean definition$getIsRepairable(ItemStack toRepair, ItemStack repair) {
        if (repair.getItem() instanceof IGTTool) {
            return getToolMaterial(toRepair) == ((IGTTool) repair.getItem()).getToolMaterial(repair);
        }
        MaterialStack repairMaterialStack = OreDictUnifier.getMaterial(repair);
        return repairMaterialStack != null && repairMaterialStack.material == getToolMaterial(toRepair);
    }

    default Multimap<String, AttributeModifier> definition$getAttributeModifiers(EntityEquipmentSlot equipmentSlot, ItemStack stack) {
        Multimap<String, AttributeModifier> multimap = HashMultimap.create();
        if (equipmentSlot == EntityEquipmentSlot.MAINHAND) {
            multimap.put(SharedMonsterAttributes.ATTACK_DAMAGE.getName(), new AttributeModifier(ATTACK_DAMAGE_MODIFIER, "Weapon modifier", getTotalAttackDamage(stack), 0));
            multimap.put(SharedMonsterAttributes.ATTACK_SPEED.getName(), new AttributeModifier(ATTACK_SPEED_MODIFIER, "Weapon modifier", getToolStats().getAttackSpeed(stack), 0));
        }
        return multimap;
    }

    default int definition$getHarvestLevel(ItemStack stack, String toolClass, @javax.annotation.Nullable net.minecraft.entity.player.EntityPlayer player, @javax.annotation.Nullable IBlockState blockState) {
        return get().getToolClasses(stack).contains(toolClass) ? getTotalHarvestLevel(stack) : -1;
    }

    default boolean definition$canDisableShield(ItemStack stack, ItemStack shield, EntityLivingBase entity, EntityLivingBase attacker) {
        return get().getToolClasses(stack).contains("axe");
    }

    default boolean definition$doesSneakBypassUse(@Nonnull ItemStack stack, @Nonnull IBlockAccess world, @Nonnull BlockPos pos, @Nonnull EntityPlayer player) {
        return getToolStats().doesSneakBypassUse();
    }

    default boolean definition$shouldCauseBlockBreakReset(ItemStack oldStack, ItemStack newStack) {
        return oldStack.getItem() != newStack.getItem();
    }

    default boolean definition$hasContainerItem(ItemStack stack) {
        return true;
    }

    default ItemStack definition$getContainerItem(ItemStack stack) {
        int damage = getToolStats().getToolDamagePerCraft(stack);
        if (damage > 0) {
            EntityPlayer player = ForgeHooks.getCraftingPlayer();
            damageItem(stack, player, damage);
            playCraftingSound(player);
            // We cannot simply return the copied stack here because Forge's bug
            // Introduced here: https://github.com/MinecraftForge/MinecraftForge/pull/3388
            // Causing PlayerDestroyItemEvent to never be fired under correct circumstances.
            // While preliminarily fixing ItemStack being null in ForgeHooks#getContainerItem in the PR
            // The semantics was misunderstood, any stack that are "broken" (damaged beyond maxDamage)
            // Will be "empty" ItemStacks (while not == ItemStack.EMPTY, but isEmpty() == true)
            // PlayerDestroyItemEvent will not be fired correctly because of this oversight.
            if (stack.isEmpty()) { // Equal to listening to PlayerDestroyItemEvent
                return getToolStats().getBrokenStack();
            }
        }
        return stack.copy();
    }

    /**
     * Damages the tool appropriately
     *
     * @param stack  Tool ItemStack
     * @param entity Entity that has damaged this ItemStack
     * @param damage Damage the ItemStack will be taking
     */
    default void damageItem(ItemStack stack, EntityLivingBase entity, int damage) {
        if (stack.getTagCompound() != null && stack.getTagCompound().getBoolean("Unbreakable")) {
            return;
        }
        if (!(entity instanceof EntityPlayer) || !((EntityPlayer) entity).capabilities.isCreativeMode) {
            if (isElectric()) {
                int electricDamage = damage * ConfigHolder.machines.energyUsageMultiplier;
                IElectricItem electricItem = stack.getCapability(GregtechCapabilities.CAPABILITY_ELECTRIC_ITEM, null);
                if (electricItem != null) {
                    electricItem.discharge(electricDamage, getElectricTier(), true, false, false);
                    if (electricItem.getCharge() > 0 && entity.getRNG().nextInt(100) > ConfigHolder.tools.rngDamageElectricTools) {
                        return;
                    }
                } else {
                    throw new IllegalStateException("Electric tool does not have an attached electric item capability.");
                }
            }
            int unbreakingLevel = EnchantmentHelper.getEnchantmentLevel(Enchantments.UNBREAKING, stack);
            int negated = 0;
            for (int k = 0; unbreakingLevel > 0 && k < damage; k++) {
                if (EnchantmentDurability.negateDamage(stack, unbreakingLevel, entity.getRNG())) {
                    negated++;
                }
            }
            damage -= negated;
            if (damage <= 0) {
                return;
            }
            int newDurability = definition$getDamage(stack) + damage;
            if (entity instanceof EntityPlayerMP) {
                CriteriaTriggers.ITEM_DURABILITY_CHANGED.trigger((EntityPlayerMP) entity, stack, newDurability);
            }
            definition$setDamage(stack, newDurability);
            if (newDurability > definition$getMaxDamage(stack)) {
                if (entity instanceof EntityPlayer) {
                    EntityPlayer entityplayer = (EntityPlayer) entity;
                    entityplayer.addStat(StatList.getObjectBreakStats(stack.getItem()));
                }
                entity.renderBrokenItemStack(stack);
                stack.shrink(1);
            }
        }
    }

    default boolean definition$isDamaged(ItemStack stack) {
        return definition$getDamage(stack) > 0;
    }

    default int definition$getDamage(ItemStack stack) {
        NBTTagCompound toolTag = getToolTag(stack);
        if (toolTag.hasKey("Durability", Constants.NBT.TAG_INT)) {
            return toolTag.getInteger("Durability");
        }
        toolTag.setInteger("Durability", 0);
        return 0;
    }

    default int definition$getMaxDamage(ItemStack stack) {
        return getTotalMaxDurability(stack);
    }

    default void definition$setDamage(ItemStack stack, int durability) {
        NBTTagCompound toolTag = getToolTag(stack);
        toolTag.setInteger("Durability", durability);
    }

    @Nullable
    default ICapabilityProvider definition$initCapabilities(ItemStack stack, @Nullable NBTTagCompound nbt) {
        return isElectric() ? ElectricStats.createElectricItem(0L, getElectricTier()).createProvider(stack) : null;
    }

    default ActionResult<ItemStack> definition$onItemRightClick(World world, EntityPlayer player, EnumHand hand) {
        ItemStack stack = player.getHeldItem(hand);
        if (!world.isRemote) {
            new PlayerInventoryHolder(player, hand).openUI();
            return ActionResult.newResult(EnumActionResult.SUCCESS, stack);
        }
        return ActionResult.newResult(EnumActionResult.PASS, stack);
    }

    @SideOnly(Side.CLIENT)
    default void definition$addInformation(ItemStack stack, @Nullable World world, List<String> tooltip, ITooltipFlag flag) {
        // if (flag.isAdvanced()) TODO: lists "behaviours"
    }

    @SideOnly(Side.CLIENT)
    default void renderElectricBar(@Nonnull ItemStack stack, int xPosition, int yPosition) {
        if (isElectric()) {
            long maxCharge = getMaxCharge(stack);
            if (maxCharge != -1L) {
                long charge = getCharge(stack);
                if (charge < maxCharge) {
                    double level = (double) charge / (double) maxCharge;
                    boolean showDurability = stack.getItem().showDurabilityBar(stack);
                    ToolChargeBarRenderer.render(level, xPosition, yPosition, showDurability ? 2 : 0, true);
                }
            }
        }
    }

    // Sound Playing
    default void playCraftingSound(EntityPlayer player) {
        if (ConfigHolder.client.toolCraftingSounds && getSound() != null) {
            if (!player.getCooldownTracker().hasCooldown(get())) {
                player.getCooldownTracker().setCooldown(get(), 10);
                player.getEntityWorld().playSound(null, player.posX, player.posY, player.posZ, getSound(), SoundCategory.PLAYERS, 1F, 1F);
            }
        }
    }

    default void playSound(EntityPlayer player) {
        if (ConfigHolder.client.toolUseSounds && getSound() != null) {
            player.getEntityWorld().playSound(null, player.posX, player.posY, player.posZ, getSound(), SoundCategory.PLAYERS, 1F, 1F);
        }
    }

    // AoE
    default Set<BlockPos> getHarvestableBlocks(ItemStack stack, AoEDefinition aoeDefinition, @Nonnull World world, @Nonnull EntityPlayer player, RayTraceResult rayTraceResult) {
        if (rayTraceResult != null && rayTraceResult.typeOfHit == RayTraceResult.Type.BLOCK && rayTraceResult.sideHit != null) {
            int column = aoeDefinition.column;
            int row = aoeDefinition.row;
            int layer = aoeDefinition.layer;
            EnumFacing playerFacing = player.getHorizontalFacing();
            EnumFacing.Axis playerAxis = playerFacing.getAxis();
            EnumFacing.Axis sideHitAxis = rayTraceResult.sideHit.getAxis();
            EnumFacing.AxisDirection sideHitAxisDir = rayTraceResult.sideHit.getAxisDirection();
            ImmutableSet.Builder<BlockPos> validPositions = ImmutableSet.builder();
            if (sideHitAxis.isVertical()) {
                boolean isX = playerAxis == EnumFacing.Axis.X;
                boolean isDown = sideHitAxisDir == EnumFacing.AxisDirection.NEGATIVE;
                for (int y = 0; y <= layer; y++) {
                    for (int x = isX ? -row : -column; x <= (isX ? row : column); x++) {
                        for (int z = isX ? -column : -row; z <= (isX ? column : row); z++) {
                            if (!(x == 0 && y == 0 && z == 0)) {
                                BlockPos pos = rayTraceResult.getBlockPos().add(x, isDown ? y : -y, z);
                                IBlockState state = world.getBlockState(pos);
                                if (state.getBlock().canHarvestBlock(world, pos, player)) {
                                    if (get().getToolClasses(stack).stream().anyMatch(s -> state.getBlock().isToolEffective(s, state))) {
                                        validPositions.add(pos);
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                boolean isX = sideHitAxis == EnumFacing.Axis.X;
                boolean isNegative = sideHitAxisDir == EnumFacing.AxisDirection.NEGATIVE;
                for (int x = 0; x <= layer; x++) {
                    // Special case for any additional column > 1: https://i.imgur.com/Dvcx7Vg.png
                    // Same behaviour as the Flux Bore
                    for (int y = (row == 0 ? 0 : -1); y <= (row == 1 ? 1 : row + 1); y++) {
                        for (int z = -column; z <= column; z++) {
                            if (!(x == 0 && y == 0 && z == 0)) {
                                BlockPos pos = rayTraceResult.getBlockPos().add(isX ? (isNegative ? x : -x) : (isNegative ? z : -z), y, isX ? (isNegative ? z : -z) : (isNegative ? x : -x));
                                IBlockState state = world.getBlockState(pos);
                                if (state.getBlock().canHarvestBlock(world, pos, player)) {
                                    if (get().getToolClasses(stack).stream().anyMatch(s -> state.getBlock().isToolEffective(s, state))) {
                                        validPositions.add(pos);
                                    }
                                }
                            }
                        }
                    }
                }
            }
            return validPositions.build();
        }
        return Collections.emptySet();
    }

    default Set<BlockPos> getHarvestableBlocks(ItemStack stack, @Nonnull World world, @Nonnull EntityPlayer player, RayTraceResult rayTraceResult) {
        AoEDefinition aoeDefinition = getAoEDefinition(stack);
        if (aoeDefinition == AoEDefinition.none()) {
            return Collections.emptySet();
        }
        return getHarvestableBlocks(stack, aoeDefinition, world, player, rayTraceResult);
    }

    default Set<BlockPos> getHarvestableBlocks(ItemStack stack, @Nonnull World world, @Nonnull EntityPlayer player) {
        AoEDefinition aoeDefiniton = getAoEDefinition(stack);
        if (aoeDefiniton == AoEDefinition.none()) {
            return Collections.emptySet();
        }
        Vec3d lookPos = player.getPositionEyes(1F);
        Vec3d rotation = player.getLook(1);
        Vec3d realLookPos = lookPos.add(rotation.x * 5, rotation.y * 5, rotation.z * 5);
        RayTraceResult rayTraceResult = world.rayTraceBlocks(lookPos, realLookPos);
        return getHarvestableBlocks(stack, aoeDefiniton, world, player, rayTraceResult);
    }

    default boolean treeLogging(World world, EntityPlayerMP player, ItemStack stack, BlockPos start) {
        LinkedList<BlockPos> blocks = new LinkedList<>();
        Set<BlockPos> visited = new ObjectOpenHashSet<>();
        blocks.add(start);
        BlockPos pos;
        int amount = 0;
        boolean harvested = false;
        while (!blocks.isEmpty() && amount <= 512) {
            if (stack.isEmpty()) {
                return harvested;
            }
            pos = blocks.remove();
            if (!visited.add(pos)) {
                continue;
            }
            IBlockState state = world.getBlockState(pos);
            if (state.getBlock() instanceof BlockLog || OreDictUnifier.getOreDictionaryNames(new ItemStack(state.getBlock())).contains("logWood")) {
                for (int x = 0; x < 3; x++) {
                    for (int z = 0; z < 3; z++) {
                        BlockPos branchPos = pos.add(-1 + x, 1, -1 + z);
                        if (!visited.contains(branchPos)) {
                            blocks.add(branchPos);
                        }
                    }
                }
                for (EnumFacing facing : EnumFacing.HORIZONTALS) {
                    BlockPos offsetPos = pos.offset(facing);
                    if (!visited.contains(offsetPos)) {
                        blocks.add(offsetPos);
                    }
                }
                amount++;
                if (player.interactionManager.tryHarvestBlock(pos)) {
                    harvested = true;
                } else {
                    break;
                }
            }
        }
        return harvested;
    }

    default ModularUI createUI(PlayerInventoryHolder holder, EntityPlayer entityPlayer) {
        NBTTagCompound tag = getToolTag(holder.getCurrentItem());
        AoEDefinition defaultDefinition = getMaxAoEDefinition(holder.getCurrentItem());
        return ModularUI.builder(GuiTextures.BORDERED_BACKGROUND, 120, 80)
                .label(10, 10, "Column")
                .label(52, 10, "Row")
                .label(82, 10, "Layer")
                .widget(new ClickButtonWidget(15, 24, 20, 20, "+", data -> {
                    AoEDefinition.increaseColumn(tag, defaultDefinition);
                    holder.markAsDirty();
                }))
                .widget(new ClickButtonWidget(15, 44, 20, 20, "-", data -> {
                    AoEDefinition.decreaseColumn(tag, defaultDefinition);
                    holder.markAsDirty();
                }))
                .widget(new ClickButtonWidget(50, 24, 20, 20, "+", data -> {
                    AoEDefinition.increaseRow(tag, defaultDefinition);
                    holder.markAsDirty();
                }))
                .widget(new ClickButtonWidget(50, 44, 20, 20, "-", data -> {
                    AoEDefinition.decreaseRow(tag, defaultDefinition);
                    holder.markAsDirty();
                }))
                .widget(new ClickButtonWidget(85, 24, 20, 20, "+", data -> {
                    AoEDefinition.increaseLayer(tag, defaultDefinition);
                    holder.markAsDirty();
                }))
                .widget(new ClickButtonWidget(85, 44, 20, 20, "-", data -> {
                    AoEDefinition.decreaseLayer(tag, defaultDefinition);
                    holder.markAsDirty();
                }))
                .widget(new DynamicLabelWidget(23, 65, () -> Integer.toString(AoEDefinition.getColumn(getToolTag(holder.getCurrentItem()), defaultDefinition))))
                .widget(new DynamicLabelWidget(58, 65, () -> Integer.toString(AoEDefinition.getRow(getToolTag(holder.getCurrentItem()), defaultDefinition))))
                .widget(new DynamicLabelWidget(93, 65, () -> Integer.toString(AoEDefinition.getLayer(getToolTag(holder.getCurrentItem()), defaultDefinition))))
                .build(holder, entityPlayer);
    }

    // Extended Interfaces

    // IAEWrench
    /**
     * Check if the wrench can be used.
     *
     * @param player wrenching player
     * @param pos    of block.
     * @return true if wrench can be used
     */
    @Override
    default boolean canWrench(ItemStack wrench, EntityPlayer player, BlockPos pos) {
        return get().getToolClasses(wrench).contains("wrench");
    }

    // IToolWrench
    /*** Called to ensure that the wrench can be used.
     *
     * @param player - The player doing the wrenching
     * @param hand - Which hand was holding the wrench
     * @param wrench - The item stack that holds the wrench
     * @param rayTrace - The object that is being wrenched
     *
     * @return true if wrenching is allowed, false if not */
    @Override
    default boolean canWrench(EntityPlayer player, EnumHand hand, ItemStack wrench, RayTraceResult rayTrace) {
        return get().getToolClasses(wrench).contains("wrench");
    }

    /*** Callback after the wrench has been used. This can be used to decrease durability or for other purposes.
     *
     * @param player - The player doing the wrenching
     * @param hand - Which hand was holding the wrench
     * @param wrench - The item stack that holds the wrench
     * @param rayTrace - The object that is being wrenched */
    @Override
    default void wrenchUsed(EntityPlayer player, EnumHand hand, ItemStack wrench, RayTraceResult rayTrace) { }

    // IToolHammer
    @Override
    default boolean isUsable(ItemStack item, EntityLivingBase user, BlockPos pos) {
        return get().getToolClasses(item).contains("wrench");
    }

    @Override
    default boolean isUsable(ItemStack item, EntityLivingBase user, Entity entity) {
        return get().getToolClasses(item).contains("wrench");
    }

    @Override
    default void toolUsed(ItemStack item, EntityLivingBase user, BlockPos pos) { }

    @Override
    default void toolUsed(ItemStack item, EntityLivingBase user, Entity entity) { }

    // ITool
    @Override
    default boolean canUse(@Nonnull EnumHand hand, @Nonnull EntityPlayer player, @Nonnull BlockPos pos) {
        return get().getToolClasses(player.getHeldItem(hand)).contains("wrench");
    }

    @Override
    default void used(@Nonnull EnumHand hand, @Nonnull EntityPlayer player, @Nonnull BlockPos pos) { }

    // IHideFacades
    @Override
    default boolean shouldHideFacades(@Nonnull ItemStack stack, @Nonnull EntityPlayer player) {
        return get().getToolClasses(stack).contains("wrench");
    }

    // IToolGrafter
    /**
     * Called by leaves to determine the increase in sapling droprate.
     *
     * @param stack ItemStack containing the grafter.
     * @param world Minecraft world the player and the target block inhabit.
     * @param pos   Coordinate of the broken leaf block.
     * @return Float representing the factor the usual drop chance is to be multiplied by.
     */
    @Override
    default float getSaplingModifier(ItemStack stack, World world, EntityPlayer player, BlockPos pos) {
        return get().getToolClasses(stack).contains("grafter") ? 100F : 1.0F;
    }

    // IOverlayRenderAware
    @Override
    default void renderItemOverlayIntoGUI(@Nonnull ItemStack stack, int xPosition, int yPosition) {
        renderElectricBar(stack, xPosition, yPosition);
    }
}