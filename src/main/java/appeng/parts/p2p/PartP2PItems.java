/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2013 - 2015, AlgorithmX2, All rights reserved.
 *
 * Applied Energistics 2 is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Applied Energistics 2 is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Applied Energistics 2.  If not, see <http://www.gnu.org/licenses/lgpl>.
 */

package appeng.parts.p2p;


import java.util.LinkedList;
import java.util.List;

import appeng.util.inv.AdaptorIInventory;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.ISidedInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.text.ITextComponent;

import appeng.api.networking.IGridNode;
import appeng.api.networking.events.MENetworkBootingStatusChange;
import appeng.api.networking.events.MENetworkChannelsChanged;
import appeng.api.networking.events.MENetworkEventSubscribe;
import appeng.api.networking.events.MENetworkPowerStatusChange;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.networking.ticking.TickingRequest;
import appeng.api.parts.IPartModel;
import appeng.core.settings.TickRates;
import appeng.items.parts.PartModels;
import appeng.me.GridAccessException;
import appeng.me.cache.helpers.TunnelCollection;
import appeng.tile.inventory.AppEngNullInventory;
import appeng.util.Platform;
import appeng.util.inv.WrapperChainedInventory;
import appeng.util.inv.WrapperMCISidedInventory;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;

import javax.annotation.Nonnull;


// TODO: BC Integration
//@Interface( iface = "buildcraft.api.transport.IPipeConnection", iname = IntegrationType.BuildCraftTransport )
public class PartP2PItems extends PartP2PTunnel<PartP2PItems> implements /* IPipeConnection, */ISidedInventory, IGridTickable, IItemHandler
{

	private static final P2PModels MODELS = new P2PModels( "part/p2p/p2p_tunnel_items" );

	@PartModels
	public static List<IPartModel> getModels()
	{
		return MODELS.getModels();
	}

	private final LinkedList<IInventory> which = new LinkedList<IInventory>();
	private int oldSize = 0;
	private boolean requested;
	private IInventory cachedInv;

	public PartP2PItems( final ItemStack is )
	{
		super( is );
	}

	@Override
	public void onNeighborChanged()
	{
		this.cachedInv = null;
		final PartP2PItems input = this.getInput();
		if( input != null && this.isOutput() )
		{
			input.onTunnelNetworkChange();
		}
	}

	private IInventory getDestination()
	{
		this.requested = true;

		if( this.cachedInv != null )
		{
			return this.cachedInv;
		}

		final List<IInventory> outs = new LinkedList<IInventory>();
		final TunnelCollection<PartP2PItems> itemTunnels;

		try
		{
			itemTunnels = this.getOutputs();
		}
		catch( final GridAccessException e )
		{
			return new AppEngNullInventory();
		}

		for( final PartP2PItems t : itemTunnels )
		{
			final IInventory inv = t.getOutputInv();
			if( inv != null )
			{
				if( Platform.getRandomInt() % 2 == 0 )
				{
					outs.add( inv );
				}
				else
				{
					outs.add( 0, inv );
				}
			}
		}

		return this.cachedInv = new WrapperChainedInventory( outs );
	}

	private IInventory getOutputInv()
	{
		IInventory output = null;

		if( this.getProxy().isActive() )
		{
			final TileEntity te = this.getTile().getWorld().getTileEntity( this.getTile().getPos().offset( this.getSide().getFacing() ) );

			if( this.which.contains( this ) )
			{
				return null;
			}

			this.which.add( this );

			if( output == null )
			{
				if( te instanceof TileEntityChest )
				{
					output = Platform.GetChestInv( te );
				}
				else if( te instanceof ISidedInventory )
				{
					output = new WrapperMCISidedInventory( (ISidedInventory) te, this.getSide().getFacing().getOpposite() );
				}
				else if( te instanceof IInventory )
				{
					output = (IInventory) te;
				}
			}

			this.which.pop();
		}

		return output;
	}

	@Override
	public TickingRequest getTickingRequest( final IGridNode node )
	{
		return new TickingRequest( TickRates.ItemTunnel.getMin(), TickRates.ItemTunnel.getMax(), false, false );
	}

	@Override
	public TickRateModulation tickingRequest( final IGridNode node, final int ticksSinceLastCall )
	{
		final boolean wasReq = this.requested;

		if( this.requested && this.cachedInv != null )
		{
			( (WrapperChainedInventory) this.cachedInv ).cycleOrder();
		}

		this.requested = false;
		return wasReq ? TickRateModulation.FASTER : TickRateModulation.SLOWER;
	}

	@MENetworkEventSubscribe
	public void changeStateA( final MENetworkBootingStatusChange bs )
	{
		if( !this.isOutput() )
		{
			this.cachedInv = null;
			final int olderSize = this.oldSize;
			this.oldSize = this.getDestination().getSizeInventory();
			if( olderSize != this.oldSize )
			{
				this.getHost().notifyNeighbors();
			}
		}
	}

	@MENetworkEventSubscribe
	public void changeStateB( final MENetworkChannelsChanged bs )
	{
		if( !this.isOutput() )
		{
			this.cachedInv = null;
			final int olderSize = this.oldSize;
			this.oldSize = this.getDestination().getSizeInventory();
			if( olderSize != this.oldSize )
			{
				this.getHost().notifyNeighbors();
			}
		}
	}

	@MENetworkEventSubscribe
	public void changeStateC( final MENetworkPowerStatusChange bs )
	{
		if( !this.isOutput() )
		{
			this.cachedInv = null;
			final int olderSize = this.oldSize;
			this.oldSize = this.getDestination().getSizeInventory();
			if( olderSize != this.oldSize )
			{
				this.getHost().notifyNeighbors();
			}
		}
	}

	@Override
	public void onTunnelNetworkChange()
	{
		if( !this.isOutput() )
		{
			this.cachedInv = null;
			final int olderSize = this.oldSize;
			this.oldSize = this.getDestination().getSizeInventory();
			if( olderSize != this.oldSize )
			{
				this.getHost().notifyNeighbors();
			}
		}
		else
		{
			final PartP2PItems input = this.getInput();
			if( input != null )
			{
				input.getHost().notifyNeighbors();
			}
		}
	}

	@Override
	public int[] getSlotsForFace( final EnumFacing side )
	{
		final int[] slots = new int[this.getSizeInventory()];
		for( int x = 0; x < this.getSizeInventory(); x++ )
		{
			slots[x] = x;
		}
		return slots;
	}

	@Override
	public int getSizeInventory()
	{
		return this.getDestination().getSizeInventory();
	}

	/**
	 * Returns the number of slots available
	 *
	 * @return The number of slots available
	 **/
	@Override
	public int getSlots()
	{
		return this.getSizeInventory();
	}

	@Override
	public ItemStack getStackInSlot( final int i )
	{
		return this.getDestination().getStackInSlot( i );
	}

	/**
	 * Inserts an ItemStack into the given slot and return the remainder.
	 * The ItemStack should not be modified in this function!
	 * Note: This behaviour is subtly different from IFluidHandlers.fill()
	 *
	 * @param slot     Slot to insert into.
	 * @param stack    ItemStack to insert.
	 * @param simulate If true, the insertion is only simulated
	 * @return The remaining ItemStack that was not inserted (if the entire stack is accepted, then return ItemStack.EMPTY).
	 * May be the same as the input ItemStack if unchanged, otherwise a new ItemStack.
	 **/
	@Nonnull
	@Override
	public ItemStack insertItem( int slot, @Nonnull ItemStack stack, boolean simulate )
	{

		if( isItemValidForSlot( slot, stack )  )
		{
			AdaptorIInventory adaptor = new AdaptorIInventory( this.getDestination() );

			if( simulate ) {
				return adaptor.simulateAdd( stack );
			}
			else
			{
				return adaptor.addItems( stack );
			}
		}
		return stack;
	}

	/**
	 * Extracts an ItemStack from the given slot. The returned value must be null
	 * if nothing is extracted, otherwise it's stack size must not be greater than amount or the
	 * itemstacks getMaxStackSize().
	 *
	 * @param slot     Slot to extract from.
	 * @param amount   Amount to extract (may be greater than the current stacks max limit)
	 * @param simulate If true, the extraction is only simulated
	 * @return ItemStack extracted from the slot, must be ItemStack.EMPTY, if nothing can be extracted
	 **/
	@Nonnull
	@Override
	public ItemStack extractItem( int slot, int amount, boolean simulate )
	{
		return ItemStack.EMPTY;
	}

	/**
	 * Retrieves the maximum stack size allowed to exist in the given slot.
	 *
	 * @param slot Slot to query.
	 * @return The maximum stack size allowed in the slot.
	 */
	@Override
	public int getSlotLimit( int slot )
	{
		if( slot >= 0 && slot < getDestination().getSizeInventory() )
		{
			return this.getDestination().getInventoryStackLimit();
		}
		return 0;
	}

	@Override
	public ItemStack decrStackSize( final int i, final int j )
	{
		return this.getDestination().decrStackSize( i, j );
	}

	@Override
	public ItemStack removeStackFromSlot( final int i )
	{
		return ItemStack.EMPTY;
	}

	@Override
	public void setInventorySlotContents( final int i, final ItemStack itemstack )
	{
		this.getDestination().setInventorySlotContents( i, itemstack );
	}

	@Override
	public String getName()
	{
		return null;
	}

	@Override
	public boolean hasCustomName()
	{
		return false;
	}

	@Override
	public int getInventoryStackLimit()
	{
		return this.getDestination().getInventoryStackLimit();
	}

	@Override
	public void markDirty()
	{
		// eh?
	}

	@Override
	public boolean isUsableByPlayer( final EntityPlayer entityplayer )
	{
		return false;
	}

	@Override
	public void openInventory( final EntityPlayer p )
	{
	}

	@Override
	public void closeInventory( final EntityPlayer p )
	{
	}

	@Override
	public boolean hasCapability( Capability<?> capabilityClass )
	{
		if( capabilityClass == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY )
		{
			return true;
		}

		return super.hasCapability( capabilityClass );
	}

	@Override
	public <T> T getCapability( Capability<T> capabilityClass )
	{
		if( capabilityClass == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY )
		{
			return (T) this;
		}

		return super.getCapability( capabilityClass );
	}

	@Override
	public boolean isItemValidForSlot( final int i, final net.minecraft.item.ItemStack itemstack )
	{
		return this.getDestination().isItemValidForSlot( i, itemstack );
	}

	@Override
	public boolean canInsertItem( final int i, final ItemStack itemstack, final EnumFacing j )
	{
		return this.getDestination().isItemValidForSlot( i, itemstack );
	}

	@Override
	public boolean canExtractItem( final int i, final ItemStack itemstack, final EnumFacing j )
	{
		return false;
	}

	public float getPowerDrainPerTick()
	{
		return 2.0f;
	}

	@Override
	public int getField( final int id )
	{
		return 0;
	}

	// @Override
	// @Method( iname = IntegrationType.BuildCraftTransport )
	// public ConnectOverride overridePipeConnection( PipeType type, ForgeDirection with )
	// {
	// return 0;
	// }

	@Override
	public void setField( final int id, final int value )
	{

	}

	@Override
	public int getFieldCount()
	{
		return 0;
	}

	@Override
	public void clear()
	{
		// probably not...
	}

	@Override
	public ITextComponent getDisplayName()
	{
		return null;
	}

	@Override
	public IPartModel getStaticModels()
	{
		return MODELS.getModel( isPowered(), isActive() );
	}

	@Override
	public boolean isEmpty()
	{
		// TODO Auto-generated method stub
		return false;
	}

}
