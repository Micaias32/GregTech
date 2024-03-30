package gregtech.api.capability.impl;

import gregtech.api.GTValues;
import gregtech.api.capability.IMultiblockController;
import gregtech.api.capability.IMultipleTankHandler;
import gregtech.api.recipes.Recipe;
import gregtech.api.recipes.RecipeMaps;
import gregtech.api.unification.material.Materials;
import gregtech.api.util.GTLog;
import gregtech.api.util.GTUtility;
import gregtech.common.ConfigHolder;
import gregtech.common.metatileentities.multi.MetaTileEntityLargeBoiler;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.tileentity.TileEntityFurnace;
import net.minecraft.util.NonNullList;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.IFluidTank;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandlerModifiable;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;

import static gregtech.api.capability.GregtechDataCodes.BOILER_HEAT;
import static gregtech.api.capability.GregtechDataCodes.BOILER_LAST_TICK_STEAM;

public class BoilerRecipeLogic extends AbstractRecipeLogic {

    private static final long STEAM_PER_WATER = 160;

    private static final int FLUID_DRAIN_MULTIPLIER = 100;
    private static final int FLUID_BURNTIME_TO_EU = 800 / FLUID_DRAIN_MULTIPLIER;

    private int currentHeat;
    private long lastTickSteamOutput;
    private long excessWater;
    private long excessFuel;
    private long excessProjectedEU;

    public BoilerRecipeLogic(MetaTileEntityLargeBoiler tileEntity) {
        super(tileEntity, null);
        this.fluidOutputs = Collections.emptyList();
        this.itemOutputs = NonNullList.create();
    }

    @Override
    public void update() {
        if ((!isActive() || !canProgressRecipe() || !isWorkingEnabled()) && currentHeat > 0) {
            setHeat(currentHeat - 1);
            setLastTickSteam(0);
        }
        super.update();
    }

    @Override
    protected boolean canProgressRecipe() {
        return super.canProgressRecipe() && !(metaTileEntity instanceof IMultiblockController &&
                ((IMultiblockController) metaTileEntity).isStructureObstructed());
    }

    @Override
    protected void trySearchNewRecipe() {
        MetaTileEntityLargeBoiler boiler = (MetaTileEntityLargeBoiler) metaTileEntity;
        if (ConfigHolder.machines.enableMaintenance && boiler.hasMaintenanceMechanics() &&
                boiler.getNumMaintenanceProblems() > 5) {
            return;
        }

        // can optimize with an override of checkPreviousRecipe() and a check here

        IMultipleTankHandler importFluids = boiler.getImportFluids();
        List<ItemStack> dummyList = NonNullList.create();
        boolean didStartRecipe = false;

        for (IFluidTank fluidTank : importFluids.getFluidTanks()) {
            FluidStack fuelStack = fluidTank.drain(Integer.MAX_VALUE, false);
            if (fuelStack == null || CommonFluidFilters.BOILER_FLUID.test(fuelStack)) continue;

            Recipe dieselRecipe = RecipeMaps.COMBUSTION_GENERATOR_FUELS.findRecipe(
                    GTValues.V[GTValues.MAX], dummyList, Collections.singletonList(fuelStack));
            // run only if it can apply a certain amount of "parallel", this is to mitigate int division
            if (dieselRecipe != null &&
                    fuelStack.amount >= dieselRecipe.getFluidInputs().get(0).getAmount() * FLUID_DRAIN_MULTIPLIER) {
                fluidTank.drain(dieselRecipe.getFluidInputs().get(0).getAmount() * FLUID_DRAIN_MULTIPLIER, true);
                // divide by 2, as it is half burntime for combustion
                setMaxProgress(adjustBurnTimeForThrottle(Math.max(1, boiler.boilerType.runtimeBoost(
                        GTUtility.safeCastLongToInt((Math.abs(dieselRecipe.getEUt()) * dieselRecipe.getDuration()) /
                                FLUID_BURNTIME_TO_EU / 2)))));
                didStartRecipe = true;
                break;
            }

            Recipe denseFuelRecipe = RecipeMaps.SEMI_FLUID_GENERATOR_FUELS.findRecipe(
                    GTValues.V[GTValues.MAX], dummyList, Collections.singletonList(fuelStack));
            // run only if it can apply a certain amount of "parallel", this is to mitigate int division
            if (denseFuelRecipe != null &&
                    fuelStack.amount >= denseFuelRecipe.getFluidInputs().get(0).getAmount() * FLUID_DRAIN_MULTIPLIER) {
                fluidTank.drain(denseFuelRecipe.getFluidInputs().get(0).getAmount() * FLUID_DRAIN_MULTIPLIER, true);
                // multiply by 2, as it is 2x burntime for semi-fluid
                setMaxProgress(adjustBurnTimeForThrottle(
                        Math.max(1,
                                boiler.boilerType
                                        .runtimeBoost(GTUtility.safeCastLongToInt((Math.abs(denseFuelRecipe.getEUt()) *
                                                denseFuelRecipe.getDuration() / FLUID_BURNTIME_TO_EU * 2))))));
                didStartRecipe = true;
                break;
            }
        }

        if (!didStartRecipe) {
            IItemHandlerModifiable importItems = boiler.getImportItems();
            for (int i = 0; i < importItems.getSlots(); i++) {
                ItemStack stack = importItems.getStackInSlot(i);
                int fuelBurnTime = (int) Math.ceil(TileEntityFurnace.getItemBurnTime(stack));
                if (fuelBurnTime / 80 > 0) { // try to ensure this fuel can burn for at least 1 tick
                    if (FluidUtil.getFluidHandler(stack) != null) continue;
                    this.excessFuel += fuelBurnTime % 80;
                    long excessProgress = this.excessFuel / 80;
                    this.excessFuel %= 80;
                    setMaxProgress(GTUtility.safeCastLongToInt(excessProgress +
                            adjustBurnTimeForThrottle(boiler.boilerType.runtimeBoost(fuelBurnTime / 80))));
                    stack.shrink(1);
                    didStartRecipe = true;
                    break;
                }
            }
        }
        if (didStartRecipe) {
            this.progressTime = 1;
            this.recipeEUt = adjustEUtForThrottle(boiler.boilerType.steamPerTick());
            if (wasActiveAndNeedsUpdate) {
                wasActiveAndNeedsUpdate = false;
            } else {
                setActive(true);
            }
        }
        metaTileEntity.getNotifiedItemInputList().clear();
        metaTileEntity.getNotifiedFluidInputList().clear();
    }

    @Override
    protected void updateRecipeProgress() {
        if (canRecipeProgress) {
            long generatedSteam = this.recipeEUt * getMaximumHeatFromMaintenance() / getMaximumHeat();
            if (generatedSteam > 0) {
                long amount = (generatedSteam + STEAM_PER_WATER) / STEAM_PER_WATER;
                excessWater += amount * STEAM_PER_WATER - generatedSteam;
                amount -= excessWater / STEAM_PER_WATER;
                excessWater %= STEAM_PER_WATER;

                FluidStack drainedWater = getBoilerFluidFromContainer(getInputTank(),
                        GTUtility.safeCastLongToInt(amount));
                if (amount != 0 && (drainedWater == null || drainedWater.amount < amount)) {
                    getMetaTileEntity().explodeMultiblock((1.0f * currentHeat / getMaximumHeat()) * 8.0f);
                } else {
                    setLastTickSteam(generatedSteam);
                    getOutputTank().fill(Materials.Steam.getFluid(GTUtility.safeCastLongToInt(generatedSteam)), true);
                }
            }
            if (currentHeat < getMaximumHeat()) {
                setHeat(currentHeat + 1);
            }

            if (++progressTime > maxProgressTime) {
                completeRecipe();
            }
        }
    }

    private int getMaximumHeatFromMaintenance() {
        if (ConfigHolder.machines.enableMaintenance) {
            return (int) Math.min(currentHeat,
                    (1 - 0.1 * getMetaTileEntity().getNumMaintenanceProblems()) * getMaximumHeat());
        } else return currentHeat;
    }

    private long adjustEUtForThrottle(long rawEUt) {
        int throttle = ((MetaTileEntityLargeBoiler) metaTileEntity).getThrottle();
        return Math.max(25, (long) (rawEUt * (throttle / 100.0)));
    }

    private int adjustBurnTimeForThrottle(int rawBurnTime) {
        MetaTileEntityLargeBoiler boiler = (MetaTileEntityLargeBoiler) metaTileEntity;
        long EUt = boiler.boilerType.steamPerTick();
        long adjustedEUt = adjustEUtForThrottle(EUt);
        long adjustedBurnTime = rawBurnTime * EUt / adjustedEUt;
        this.excessProjectedEU += EUt * rawBurnTime - adjustedEUt * adjustedBurnTime;
        adjustedBurnTime += this.excessProjectedEU / adjustedEUt;
        this.excessProjectedEU %= adjustedEUt;
        return GTUtility.safeCastLongToInt(adjustedBurnTime);
    }

    private int getMaximumHeat() {
        return ((MetaTileEntityLargeBoiler) metaTileEntity).boilerType.getTicksToBoiling();
    }

    public int getHeatScaled() {
        return (int) Math.round(currentHeat / (1.0 * getMaximumHeat()) * 100);
    }

    public void setHeat(int heat) {
        if (heat != this.currentHeat && !metaTileEntity.getWorld().isRemote) {
            writeCustomData(BOILER_HEAT, b -> b.writeVarInt(heat));
        }
        this.currentHeat = heat;
    }

    public long getLastTickSteam() {
        return lastTickSteamOutput;
    }

    public void setLastTickSteam(long lastTickSteamOutput) {
        if (lastTickSteamOutput != this.lastTickSteamOutput && !metaTileEntity.getWorld().isRemote) {
            writeCustomData(BOILER_LAST_TICK_STEAM, b -> b.writeLong(lastTickSteamOutput));
        }
        this.lastTickSteamOutput = lastTickSteamOutput;
    }

    @Override
    public long getInfoProviderEUt() {
        return this.lastTickSteamOutput;
    }

    @Override
    public boolean consumesEnergy() {
        return false;
    }

    @Override
    public void invalidate() {
        super.invalidate();
        setLastTickSteam(0);
    }

    @Override
    protected void completeRecipe() {
        progressTime = 0;
        setMaxProgress(0);
        recipeEUt = 0;
        wasActiveAndNeedsUpdate = true;
    }

    @NotNull
    @Override
    public MetaTileEntityLargeBoiler getMetaTileEntity() {
        return (MetaTileEntityLargeBoiler) super.getMetaTileEntity();
    }

    @NotNull
    @Override
    public NBTTagCompound serializeNBT() {
        NBTTagCompound compound = super.serializeNBT();
        compound.setInteger("Heat", currentHeat);
        compound.setLong("ExcessFuel", excessFuel);
        compound.setLong("ExcessWater", excessWater);
        compound.setLong("ExcessProjectedEU", excessProjectedEU);
        return compound;
    }

    @Override
    public void deserializeNBT(@NotNull NBTTagCompound compound) {
        super.deserializeNBT(compound);
        this.currentHeat = compound.getInteger("Heat");
        this.excessFuel = compound.getLong("ExcessFuel");
        this.excessWater = compound.getLong("ExcessWater");
        this.excessProjectedEU = compound.getLong("ExcessProjectedEU");
    }

    @Override
    public void writeInitialSyncData(@NotNull PacketBuffer buf) {
        super.writeInitialSyncData(buf);
        buf.writeVarInt(currentHeat);
        buf.writeLong(lastTickSteamOutput);
    }

    @Override
    public void receiveInitialSyncData(@NotNull PacketBuffer buf) {
        super.receiveInitialSyncData(buf);
        this.currentHeat = buf.readVarInt();
        this.lastTickSteamOutput = buf.readLong();
    }

    @Override
    public void receiveCustomData(int dataId, @NotNull PacketBuffer buf) {
        super.receiveCustomData(dataId, buf);
        if (dataId == BOILER_HEAT) {
            this.currentHeat = buf.readVarInt();
        } else if (dataId == BOILER_LAST_TICK_STEAM) {
            this.lastTickSteamOutput = buf.readLong();
        }
    }

    // Required overrides to use RecipeLogic, but all of them are redirected by the above overrides.

    @Override
    protected long getEnergyInputPerSecond() {
        GTLog.logger.error("Large Boiler called getEnergyInputPerSecond(), this should not be possible!");
        return 0;
    }

    @Override
    protected long getEnergyStored() {
        GTLog.logger.error("Large Boiler called getEnergyStored(), this should not be possible!");
        return 0;
    }

    @Override
    protected long getEnergyCapacity() {
        GTLog.logger.error("Large Boiler called getEnergyCapacity(), this should not be possible!");
        return 0;
    }

    @Override
    protected boolean drawEnergy(long recipeEUt, boolean simulate) {
        GTLog.logger.error("Large Boiler called drawEnergy(), this should not be possible!");
        return false;
    }

    @Override
    public long getMaxVoltage() {
        GTLog.logger.error("Large Boiler called getMaxVoltage(), this should not be possible!");
        return 0;
    }

    /**
     * @param fluidHandler the handler to drain from
     * @param amount       the amount to drain
     * @return a valid boiler fluid from a container
     */
    @Nullable
    private static FluidStack getBoilerFluidFromContainer(@NotNull IFluidHandler fluidHandler, int amount) {
        if (amount == 0) return null;
        FluidStack drainedWater = fluidHandler.drain(Materials.Water.getFluid(amount), true);
        if (drainedWater == null || drainedWater.amount == 0) {
            drainedWater = fluidHandler.drain(Materials.DistilledWater.getFluid(amount), true);
        }
        if (drainedWater == null || drainedWater.amount == 0) {
            for (String fluidName : ConfigHolder.machines.boilerFluids) {
                Fluid fluid = FluidRegistry.getFluid(fluidName);
                if (fluid != null) {
                    drainedWater = fluidHandler.drain(new FluidStack(fluid, amount), true);
                    if (drainedWater != null && drainedWater.amount > 0) {
                        break;
                    }
                }
            }
        }
        return drainedWater;
    }
}
