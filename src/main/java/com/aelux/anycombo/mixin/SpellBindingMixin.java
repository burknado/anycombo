package com.aelux.anycombo.mixin;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.spell_engine.SpellEngineMod;
import net.spell_engine.api.spell.container.SpellContainerHelper;
import net.spell_engine.api.spell.Spell;
import net.spell_engine.api.spell.registry.SpellRegistry;
import net.spell_engine.api.tags.SpellEngineItemTags;
import net.spell_engine.spellbinding.SpellBinding;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Mixin(SpellBinding.class)
public abstract class SpellBindingMixin {

    @Shadow
    private static int rawSpellId(Level level, ResourceLocation spellId) {
        throw new AssertionError("Shadowed method should never execute");
    }

    /**
     * Lets exactly ONE off-pool spell be bound to a tome via scroll, in survival
     */
    @ModifyReturnValue(method = "offersFor", at = @At("RETURN"))
    private static SpellBinding.OfferResult anycombo$allowOneOffClassScroll(
            SpellBinding.OfferResult original,
            Level level,
            boolean creative,
            ItemStack itemStack,
            ItemStack consumableStack,
            int libraryPower
    ) {
        if (original.mode() != SpellBinding.Mode.SPELL) {
            return original;
        }

        var consumableContainer =
                SpellContainerHelper.containerFromItemStack(consumableStack);

        boolean scrollMode =
                consumableStack.is(SpellEngineItemTags.SPELL_BOOK_MERGEABLE)
                        && consumableContainer != null;

        if (!scrollMode) {
            return original;
        }

        var container = SpellContainerHelper.containerFromItemStack(itemStack);

        if (container == null) {
            return original;
        }

        var registry = SpellRegistry.from(level);
        var pool = SpellRegistry.entries(level, container.pool());

        Set<ResourceLocation> poolIds = pool.stream()
                .map(entry -> entry.getKey().location())
                .collect(Collectors.toSet());

        boolean alreadyHasForeignSpell =
                container.spell_ids().stream().anyMatch(idString -> {
                    ResourceLocation id = safeId(idString);
                    return id != null && !poolIds.contains(id);
                });

        if (alreadyHasForeignSpell) {
            return original;
        }

        Set<Integer> alreadyOfferedRawIds = original.offers().stream()
                .map(SpellBinding.Offer::id)
                .collect(Collectors.toSet());

        var extras = new ArrayList<SpellBinding.Offer>();

        for (var idString : consumableContainer.spell_ids()) {
            ResourceLocation id = safeId(idString);

            if (id == null || poolIds.contains(id)) {
                continue;
            }

            var spell = registry.get(id);

            if (spell == null) {
                continue;
            }

            int rawId = rawSpellId(level, id);

            if (alreadyOfferedRawIds.contains(rawId)) {
                continue;
            }

            int cost = spell.tier
                    * SpellEngineMod.config.spell_scroll_level_cost_per_tier
                    + SpellEngineMod.config.spell_scroll_apply_cost_base;

            extras.add(new SpellBinding.Offer(rawId, cost, 1, 0, true));
        }

        if (extras.isEmpty()) {
            return original;
        }

        var combined = new ArrayList<>(original.offers());
        combined.addAll(extras);

        return new SpellBinding.OfferResult(original.mode(), combined);
    }

    /**
     * Adds an entry for spells bound to the item that aren't part of the normal binding pool.
     */
    @ModifyReturnValue(method = "offersFor", at = @At("RETURN"))
    private static SpellBinding.OfferResult anycombo$showForeignTierOccupants(
            SpellBinding.OfferResult original,
            Level level,
            boolean creative,
            ItemStack itemStack,
            ItemStack consumableStack,
            int libraryPower
    ) {
        if (original.mode() != SpellBinding.Mode.SPELL) {
            return original;
        }

        boolean scrollMode =
                consumableStack.is(SpellEngineItemTags.SPELL_BOOK_MERGEABLE)
                        && SpellContainerHelper.containerFromItemStack(consumableStack) != null;

        if (scrollMode) {
            return original;
        }

        var container = SpellContainerHelper.containerFromItemStack(itemStack);

        if (container == null || container.spell_ids().isEmpty()) {
            return original;
        }

        var registry = SpellRegistry.from(level);

        Set<ResourceLocation> alreadyOfferedIds = new HashSet<>();

        for (var offer : original.offers()) {
            Spell spell = registry.byId(offer.id());
            if (spell != null) {
                ResourceLocation id = registry.getKey(spell);
                if (id != null) {
                    alreadyOfferedIds.add(id);
                }
            }
        }

        var extras = new ArrayList<SpellBinding.Offer>();

        for (var idString : container.spell_ids()) {
            ResourceLocation id = safeId(idString);

            if (id == null || alreadyOfferedIds.contains(id)) {
                continue;
            }

            var spell = registry.get(id);

            if (spell == null) {
                continue;
            }

            extras.add(new SpellBinding.Offer(
                    rawSpellId(level, id),
                    0,
                    0,
                    0,
                    true
            ));
        }

        if (extras.isEmpty()) {
            return original;
        }

        var combined = new ArrayList<>(original.offers());
        combined.addAll(extras);

        return new SpellBinding.OfferResult(original.mode(), combined);
    }

    private static ResourceLocation safeId(String raw) {
        try {
            return ResourceLocation.parse(raw);
        } catch (Exception e) {
            return null;
        }
    }
}
