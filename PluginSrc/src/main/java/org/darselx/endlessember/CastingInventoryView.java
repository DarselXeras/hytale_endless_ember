package org.darselx.endlessember;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.*;
import com.hypixel.hytale.server.core.inventory.transaction.*;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

/** A live filtered view of one contiguous section of the saved machine inventory. */
final class CastingInventoryView extends DelegateItemContainer<SimpleItemContainer> {
    final int first;
    private final short size;
    private final BooleanSupplier valid;
    private final IntConsumer changed;

    CastingInventoryView(SimpleItemContainer inventory,int first,int size,BooleanSupplier valid,IntConsumer changed){
        super(inventory);
        if(first<0||size<=0||first+size>inventory.getCapacity())throw new IllegalArgumentException("Invalid machine inventory range");
        this.first=first;this.size=(short)size;this.valid=valid;this.changed=changed;
    }
    @Override public short getCapacity(){return size;}
    private short real(short slot){validateSlotIndex(slot,size);return (short)(first+slot);}
    @Override protected ItemStack internal_getSlot(short slot){return getDelegate().getItemStack(real(slot));}
    @Override protected ItemStack internal_setSlot(short slot,ItemStack stack){
        short target=real(slot);ItemStack previous=getDelegate().getItemStack(target);
        getDelegate().setItemStackForSlot(target,stack,false);changed.accept(target);return previous;
    }
    @Override protected ItemStack internal_removeSlot(short slot){return internal_setSlot(slot,null);}
    @Override protected boolean cantAddToSlot(short slot,ItemStack stack,ItemStack present){
        int target=real(slot);
        if(!valid.getAsBoolean()||!CastingMachine.accepts(target,stack))return true;
        if(ItemStack.isEmpty(stack))return false;
        if(target<10||target==CastingMachine.INPUT+1){
            int arriving=ItemStack.isEmpty(stack)?0:stack.getQuantity();
            int existing=ItemStack.isEmpty(present)?0:present.getQuantity();
            if(arriving+existing>1)return true;
        }
        return !getDelegate().canAddItemStackToSlot((short)target,stack,false,true);
    }
    @Override protected boolean cantRemoveFromSlot(short slot){real(slot);return !valid.getAsBoolean();}
    @Override protected boolean cantDropFromSlot(short slot){real(slot);return !valid.getAsBoolean();}
    @Override protected boolean cantMoveToSlot(ItemContainer from,short slot){return !valid.getAsBoolean();}
    @Override public boolean isEmpty(){
        for(short i=0;i<size;i++)if(!ItemStack.isEmpty(internal_getSlot(i)))return false;return true;
    }
    @Override protected ClearTransaction internal_clear(){
        if(!valid.getAsBoolean())return new ClearTransaction(false,size,new ItemStack[size]);
        ItemStack[] removed=new ItemStack[size];
        for(short i=0;i<size;i++)removed[i]=internal_removeSlot(i);
        return new ClearTransaction(true,size,removed);
    }
    // DelegateItemContainer.clear() clears the entire backing container; a
    // section view must only clear its own slots, including native bulk actions.
    @Override public ClearTransaction clear(){return writeAction(this::internal_clear);}
    @Override public CastingInventoryView clone(){
        return new CastingInventoryView(getDelegate().clone(),first,size,()->true,slot->{});
    }
    // Mapped writes above already notify the backing inventory with the real
    // slot index. Do not forward a second transaction with relative indices.
    @Override protected void sendUpdate(Transaction transaction){}
}
