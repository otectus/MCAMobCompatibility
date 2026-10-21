package dev.otectus.mcamobcompat.client;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.WalkAnimationState;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Puts a native actor's pose onto its MCA render view, for one frame: where it stands and faces, how
 * its legs swing, its attack and hurt animations, crouching, sprinting and swimming, its handedness
 * and aggression, its equipment, its name, and whether it sits on a mount.
 *
 * <p>Only public state is read, through vanilla accessors, and only the view is written: the actor is
 * never touched, no item use is started, and nothing real is mounted. Two things need care and are
 * contained here:
 * <ul>
 *   <li>The walk animation's fields are private, so it is reproduced exactly with its own public
 *       {@code setSpeed} and {@code update} -- see {@link #copyWalk}.</li>
 *   <li>A sitting pose needs the view to be a passenger, so the view rides a detached seat of its own
 *       (a minecart that is never in any level), never the actor's real mount.</li>
 * </ul>
 * Arm poses for held items -- a drawn bow, a charged crossbow, a raised shield -- are worked out from
 * the actor's own state and set on the renderer's model for the one render call; see
 * {@link #armPose}.
 */
final class NativePoseAdapter {

    /** Each view's private seat, for an actor that sits on a mount. Never added to a level. */
    private static final Map<Villager, Entity> SEATS = new WeakHashMap<>();

    private NativePoseAdapter() {
    }

    /**
     * Applies the actor's frame to the view. Rotation comes from {@code facing} -- the actor in the
     * world, or the entity a screen posed for a portrait -- and everything else from {@code actor}.
     */
    static void apply(LivingEntity actor, LivingEntity facing, Villager view) {
        view.setPos(actor.getX(), actor.getY(), actor.getZ());
        view.xo = actor.xo;
        view.yo = actor.yo;
        view.zo = actor.zo;
        view.xOld = actor.xOld;
        view.yOld = actor.yOld;
        view.zOld = actor.zOld;

        view.setYRot(facing.getYRot());
        view.yRotO = facing.yRotO;
        view.setXRot(facing.getXRot());
        view.xRotO = facing.xRotO;
        view.yBodyRot = facing.yBodyRot;
        view.yBodyRotO = facing.yBodyRotO;
        view.yHeadRot = facing.yHeadRot;
        view.yHeadRotO = facing.yHeadRotO;

        copyWalk(actor.walkAnimation, view.walkAnimation);
        view.swinging = actor.swinging;
        view.swingingArm = actor.swingingArm;
        view.swingTime = actor.swingTime;
        view.attackAnim = actor.attackAnim;
        view.oAttackAnim = actor.oAttackAnim;
        view.hurtTime = actor.hurtTime;
        view.hurtDuration = actor.hurtDuration;
        view.deathTime = actor.deathTime;
        view.tickCount = actor.tickCount;

        if (view.getPose() != actor.getPose()) {
            view.setPose(actor.getPose());
        }
        view.setShiftKeyDown(actor.isShiftKeyDown());
        view.setSprinting(actor.isSprinting());
        view.setSwimming(actor.isSwimming());
        view.setInvisible(actor.isInvisible());
        view.setOnGround(actor.onGround());
        if (actor instanceof Mob mob) {
            view.setLeftHanded(mob.isLeftHanded());
            view.setAggressive(mob.isAggressive());
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack worn = actor.getItemBySlot(slot);
            if (view.getItemBySlot(slot) != worn) {
                view.setItemSlot(slot, worn);
            }
        }
        view.setCustomName(actor.getDisplayName());
        view.setCustomNameVisible(actor.isCustomNameVisible());
        seat(actor, view);
    }

    /**
     * Makes {@code to} exactly equal to {@code from} -- previous speed, speed and position -- using
     * only the public methods: {@code update(x, 0)} moves the position by the current speed, and
     * {@code update(x, 1)} makes the old speed the current one and the current one {@code x}.
     */
    static void copyWalk(WalkAnimationState from, WalkAnimationState to) {
        float speedOld = from.speed(0.0F);
        float speed = from.speed();
        float position = from.position();
        to.setSpeed(position - speed - to.position());
        to.update(0.0F, 0.0F);
        to.setSpeed(speedOld);
        to.update(speed, 1.0F);
    }

    /** Seats the view on its own detached seat while the actor sits on a mount, and stands it up after. */
    private static void seat(LivingEntity actor, Villager view) {
        Entity vehicle = actor.getVehicle();
        boolean sitting = vehicle != null && vehicle.shouldRiderSit();
        if (sitting && !view.isPassenger()) {
            Entity seat = SEATS.computeIfAbsent(view, ignored -> EntityType.MINECART.create(view.level()));
            if (seat != null) {
                view.startRiding(seat, true);
            }
        } else if (!sitting && view.isPassenger()) {
            view.stopRiding();
        }
    }

    /** The arm pose for one hand, from the actor's own item and item use, as the player renderer reads it. */
    static HumanoidModel.ArmPose armPose(LivingEntity actor, InteractionHand hand) {
        ItemStack stack = actor.getItemInHand(hand);
        if (stack.isEmpty()) {
            return HumanoidModel.ArmPose.EMPTY;
        }
        if (actor.getUsedItemHand() == hand && actor.getUseItemRemainingTicks() > 0) {
            UseAnim animation = stack.getUseAnimation();
            switch (animation) {
                case BLOCK -> {
                    return HumanoidModel.ArmPose.BLOCK;
                }
                case BOW -> {
                    return HumanoidModel.ArmPose.BOW_AND_ARROW;
                }
                case SPEAR -> {
                    return HumanoidModel.ArmPose.THROW_SPEAR;
                }
                case CROSSBOW -> {
                    return HumanoidModel.ArmPose.CROSSBOW_CHARGE;
                }
                case SPYGLASS -> {
                    return HumanoidModel.ArmPose.SPYGLASS;
                }
                case TOOT_HORN -> {
                    return HumanoidModel.ArmPose.TOOT_HORN;
                }
                case BRUSH -> {
                    return HumanoidModel.ArmPose.BRUSH;
                }
                default -> {
                }
            }
        }
        if (stack.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(stack)) {
            return HumanoidModel.ArmPose.CROSSBOW_HOLD;
        }
        if (stack.getItem() instanceof BowItem && actor instanceof Mob mob && mob.isAggressive()) {
            // Bowmen and guards draw on their target the way a skeleton does.
            return HumanoidModel.ArmPose.BOW_AND_ARROW;
        }
        return HumanoidModel.ArmPose.ITEM;
    }

    /** Sets both arms' poses on the model for the actor's handedness; returns the poses it replaced. */
    static HumanoidModel.ArmPose[] poseArms(HumanoidModel<?> model, LivingEntity actor) {
        HumanoidModel.ArmPose[] previous = {model.rightArmPose, model.leftArmPose};
        HumanoidModel.ArmPose main = armPose(actor, InteractionHand.MAIN_HAND);
        HumanoidModel.ArmPose off = armPose(actor, InteractionHand.OFF_HAND);
        if (main.isTwoHanded()) {
            off = actor.getOffhandItem().isEmpty() ? HumanoidModel.ArmPose.EMPTY : HumanoidModel.ArmPose.ITEM;
        }
        if (actor.getMainArm() == HumanoidArm.RIGHT) {
            model.rightArmPose = main;
            model.leftArmPose = off;
        } else {
            model.rightArmPose = off;
            model.leftArmPose = main;
        }
        return previous;
    }

    static void restoreArms(HumanoidModel<?> model, HumanoidModel.ArmPose[] previous) {
        model.rightArmPose = previous[0];
        model.leftArmPose = previous[1];
    }

    /** Forgets every seat, with the views. */
    static void clear() {
        SEATS.clear();
    }
}
