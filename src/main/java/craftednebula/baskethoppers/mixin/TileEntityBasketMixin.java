package craftednebula.baskethoppers.mixin;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.List;
import net.minecraft.core.block.entity.TileEntity;
import net.minecraft.core.block.entity.TileEntityBasket;
import net.minecraft.core.block.entity.TileEntityFurnace;
import net.minecraft.core.block.entity.TileEntityFurnaceBlast;
import net.minecraft.core.block.entity.TileEntityTrommel;
import net.minecraft.core.crafting.LookupFuelFurnace;
import net.minecraft.core.crafting.LookupFuelFurnaceBlast;
import net.minecraft.core.entity.Entity;
import net.minecraft.core.item.ItemStack;
import net.minecraft.core.player.inventory.container.Container;
import org.joml.primitives.AABBd;
import org.joml.primitives.AABBdc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TileEntityBasket.class)
public abstract class TileEntityBasketMixin extends TileEntity {

	@Shadow
	private Object2IntMap<Object> contents;

	@Shadow
	private void updateNumUnits() {}

	@Shadow
	private int numUnitsInside;

	@Shadow
	public int getMaxUnits() { return 1728; }

	@Shadow
	private boolean importItemStack(ItemStack stack) { return false; }

	@Unique
	private int baskethoppers$transferCooldown = 0;

	@Unique
	private static final int BATCH_SIZE = 32;

	@Unique
	private int baskethoppers$targetIndex = 0;

	// {dx, dy, dz, isSideFace (1 = side, 0 = vertical)}
	@Unique
	private static final int[][] OFFSETS = {
		{ 0, -1,  0, 0}, // 0: Down   (Vertical -> Processing)
		{ 0,  0, -1, 1}, // 1: North  (Side     -> Fuel Slot)
		{ 0,  0,  1, 1}, // 2: South  (Side     -> Fuel Slot)
		{-1,  0,  0, 1}, // 3: West   (Side     -> Fuel Slot)
		{ 1,  0,  0, 1} // 4: East   (Side     -> Fuel Slot)
	};

	@Inject(method = "tick", at = @At("TAIL"))
	private void baskethoppers$transferItems(CallbackInfo ci) {
		if (this.worldObj == null || this.worldObj.isClientSide) {
			return;
		}

		if (this.baskethoppers$transferCooldown > 0) {
			this.baskethoppers$transferCooldown--;
			return;
		}

		boolean didTransfer = false;

		//Try PULLING from block containers above OR minecart containers in the 1x2x1 pickup AABB
		if (this.numUnitsInside < this.getMaxUnits()) {
			didTransfer = baskethoppers$pullFromAbove();
		}

		//Try PUSHING to connected neighbors if basket is not empty (and didn't just pull this tick)
		if (!didTransfer && this.numUnitsInside > 0) {
			didTransfer = baskethoppers$pushToNeighbors();
		}

		if (didTransfer) {
			this.baskethoppers$transferCooldown = 8;
		}
	}

	/**
	 * Pulls up to BATCH_SIZE items from a block Container directly above (y + 1)
	 * OR any Entity Container (e.g., Chest Minecarts) within the 1x2.5x1 pickup area.
	 */
	@Unique
	private boolean baskethoppers$pullFromAbove() {
		//check for a stationary block entity container at (x, y + 1, z)
		TileEntity aboveTile = this.worldObj.getTileEntity(this.tilePos.x, this.tilePos.y + 1, this.tilePos.z);
		if (aboveTile instanceof Container) {
			Container containerAbove = (Container) aboveTile;
			int startSlot = 0;
			int endSlot = containerAbove.getContainerSize();

			//never steal raw ore or fuel
			if (containerAbove instanceof TileEntityFurnace) {
				startSlot = TileEntityFurnace.SLOT_RESULT;
				endSlot = TileEntityFurnace.SLOT_RESULT + 1;
			} else if (containerAbove instanceof TileEntityFurnaceBlast) {
				startSlot = TileEntityFurnaceBlast.SLOT_RESULT;
				endSlot = TileEntityFurnaceBlast.SLOT_RESULT + 1;
			} else if (containerAbove instanceof TileEntityTrommel) {
				return false; // Trommels suck
			}

			if (baskethoppers$pullFromContainer(containerAbove, startSlot, endSlot)) {
				return true;
			}
		}

		//scan for container entities
		AABBdc aabb = new AABBd(
			this.tilePos.x, this.tilePos.y, this.tilePos.z,
			this.tilePos.x + 1.0, this.tilePos.y + 2.5, this.tilePos.z + 1.0
		);

		List<Entity> entities = this.worldObj.getEntitiesWithinAABB(Entity.class, aabb);
		for (int i = 0; i < entities.size(); i++) {
			Entity entity = entities.get(i);
			if (entity instanceof Container && !entity.removed) {
				Container containerEntity = (Container) entity;
				if (baskethoppers$pullFromContainer(containerEntity, 0, containerEntity.getContainerSize())) {
					return true;
				}
			}
		}

		return false;
	}

	/**
	 * Shared extraction helper that pulls up to BATCH_SIZE items from a specific slot range of a Container.
	 */
	@Unique
	private boolean baskethoppers$pullFromContainer(Container container, int startSlot, int endSlot) {
		for (int i = startSlot; i < endSlot; i++) {
			ItemStack slotStack = container.getItem(i);
			if (slotStack != null && slotStack.stackSize > 0) {
				int amountToPull = Math.min(slotStack.stackSize, BATCH_SIZE);

				// Create a temporary stack with batch limit to pass into importItemStack
				ItemStack tempStack = slotStack.copy();
				tempStack.stackSize = amountToPull;

				boolean imported = this.importItemStack(tempStack);
				if (imported) {
					int taken = amountToPull - tempStack.stackSize;
					if (taken > 0) {
						slotStack.stackSize -= taken;
						if (slotStack.stackSize <= 0) {
							container.setItem(i, null);
						} else {
							container.setItem(i, slotStack);
						}

						container.setChanged();
						this.updateNumUnits();
						this.setChanged();
						this.worldObj.markBlockNeedsUpdate(this.tilePos);
						this.worldObj.notifyBlocksInRadiusOfNeighborChange(2, this.tilePos, this.getBlock());
						return true;
					}
				}
			}
		}
		return false;
	}

	/**
	 * Pushes up to BATCH_SIZE items to adjacent containers around itself
	 */
	@Unique
	private boolean baskethoppers$pushToNeighbors() {
		ObjectIterator<Object2IntMap.Entry<Object>> iterator = this.contents.object2IntEntrySet().iterator();
		if (!iterator.hasNext()) {
			return false;
		}

		Object2IntMap.Entry<Object> firstEntry = iterator.next();
		Object basketEntryObj = firstEntry.getKey();
		int currentCount = firstEntry.getIntValue();

		if (currentCount <= 0) {
			return false;
		}

		int amountToPush = Math.min(currentCount, BATCH_SIZE);
		ItemStack stackToPush = baskethoppers$createStackFromEntry(basketEntryObj, amountToPush);
		if (stackToPush == null) {
			return false;
		}

		for (int i = 0; i < 5; i++) {
			int dirIndex = (this.baskethoppers$targetIndex + i) % 5;
			int[] dir = OFFSETS[dirIndex];

			TileEntity neighborTile = this.worldObj.getTileEntity(
				this.tilePos.x + dir[0],
				this.tilePos.y + dir[1],
				this.tilePos.z + dir[2]
			);

			if (!(neighborTile instanceof Container)) {
				continue;
			}

			Container containerNeighbor = (Container) neighborTile;
			boolean isSideFace = (dir[3] == 1);

			int insertedCount = baskethoppers$insertIntoContainer(containerNeighbor, stackToPush, isSideFace);

			if (insertedCount > 0) {
				int remainingInBasket = currentCount - insertedCount;

				if (remainingInBasket <= 0) {
					this.contents.removeInt(basketEntryObj);
				} else {
					this.contents.put(basketEntryObj, remainingInBasket);
				}

				this.updateNumUnits();
				this.setChanged();
				this.worldObj.markBlockNeedsUpdate(this.tilePos);
				this.worldObj.notifyBlocksInRadiusOfNeighborChange(2, this.tilePos, this.getBlock());

				this.baskethoppers$targetIndex = (dirIndex + 1) % 5;
				return true;
			}
		}

		return false;
	}

	@Unique
	private int baskethoppers$insertIntoContainer(Container container, ItemStack stack, boolean isSideFace) {
		int originalAmount = stack.stackSize;
		int startSlot = 0;
		int endSlot = container.getContainerSize();

		//Regular Furnace
		if (container instanceof TileEntityFurnace) {
			if (isSideFace) {
				if (LookupFuelFurnace.instance.getFuelYield(stack) <= 0) {
					return 0;
				}
				startSlot = TileEntityFurnace.SLOT_FUEL;       // Slot 1
				endSlot = TileEntityFurnace.SLOT_FUEL + 1;
			} else {
				startSlot = TileEntityFurnace.SLOT_INGREDIENT; // Slot 0
				endSlot = TileEntityFurnace.SLOT_INGREDIENT + 1;
			}
		}
		//Blast Furnace
		else if (container instanceof TileEntityFurnaceBlast) {
			if (isSideFace) {
				if (LookupFuelFurnaceBlast.instance.getFuelYield(stack) <= 0) {
					return 0;
				}
				startSlot = TileEntityFurnaceBlast.SLOT_FUEL;  // Slot 2
				endSlot = TileEntityFurnaceBlast.SLOT_FUEL + 1;
			} else {
				startSlot = TileEntityFurnaceBlast.SLOT_LEFT;  // Slots 0 & 1
				endSlot = TileEntityFurnaceBlast.SLOT_RIGHT + 1;
			}
		}
		// Trommel
		else if (container instanceof TileEntityTrommel) {
			if (isSideFace) {
				if (LookupFuelFurnace.instance.getFuelYield(stack) <= 0) {
					return 0;
				}
				startSlot = TileEntityTrommel.SLOT_FUEL;       // Slot 4
				endSlot = TileEntityTrommel.SLOT_FUEL + 1;
			} else {
				startSlot = TileEntityTrommel.SLOTS_INGREDIENT[0]; // Slots 0, 1, 2, 3
				endSlot = TileEntityTrommel.SLOTS_INGREDIENT[3] + 1;
			}
		}

		//merge into existing matching stacks
		for (int i = startSlot; i < endSlot && stack.stackSize > 0; i++) {
			ItemStack slotStack = container.getItem(i);
			if (slotStack != null && slotStack.canStackWith(stack)) {
				int maxStack = Math.min(container.getMaxStackSize(), slotStack.getMaxStackSize());
				int availableSpace = maxStack - slotStack.stackSize;

				if (availableSpace > 0) {
					int toAdd = Math.min(availableSpace, stack.stackSize);
					slotStack.stackSize += toAdd;
					stack.stackSize -= toAdd;
					container.setItem(i, slotStack);
				}
			}
		}

		//place remaining items into empty valid slots
		for (int i = startSlot; i < endSlot && stack.stackSize > 0; i++) {
			if (container.getItem(i) == null) {
				int maxStack = Math.min(container.getMaxStackSize(), stack.getMaxStackSize());
				int toAdd = Math.min(maxStack, stack.stackSize);

				ItemStack newSlotStack = stack.copy();
				newSlotStack.stackSize = toAdd;
				container.setItem(i, newSlotStack);

				stack.stackSize -= toAdd;
			}
		}

		int totalInserted = originalAmount - stack.stackSize;
		if (totalInserted > 0) {
			container.setChanged();
		}

		return totalInserted;
	}

	@Unique
	private ItemStack baskethoppers$createStackFromEntry(Object basketEntry, int count) {
		try {
			int id = (int) basketEntry.getClass().getMethod("id").invoke(basketEntry);
			int metadata = (int) basketEntry.getClass().getMethod("metadata").invoke(basketEntry);
			com.mojang.nbt.tags.CompoundTag tag = (com.mojang.nbt.tags.CompoundTag) basketEntry.getClass().getMethod("tag").invoke(basketEntry);

			ItemStack stack = new ItemStack(id, count, metadata);
			if (tag != null && !tag.getValues().isEmpty()) {
				stack.setData(tag);
			}
			return stack;
		} catch (Exception e) {
			return null;
		}
	}
}
