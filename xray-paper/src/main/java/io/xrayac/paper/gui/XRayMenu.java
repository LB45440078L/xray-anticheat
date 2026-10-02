package io.xrayac.paper.gui;

import io.xrayac.core.domain.PlayerRef;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * The holder attached to every inventory this plugin opens, carrying the menu's state.
 *
 * <h2>Why a holder rather than a map of open menus</h2>
 * The previous design kept a {@code Map<UUID, MenuView>} and decided how to handle a click by looking
 * the clicking player up in it. That is fragile in a way that produced two visible bugs at once:
 *
 * <ul>
 *   <li>Opening a second menu from the first fires {@code InventoryCloseEvent} for the first, and the
 *       close handler removed the entry that had just been written for the new menu. The result was a
 *       menu on screen with no state behind it, so <b>clicks did nothing</b>.</li>
 *   <li>Because the click handler returned early when it found no entry, it also <b>never cancelled the
 *       click</b> — so the items in the window could simply be taken.</li>
 * </ul>
 *
 * <p>Attaching the state to the inventory itself removes the possibility of that class of bug
 * entirely. A click is handled by asking "is the inventory being viewed one of ours?" — a question
 * whose answer cannot go stale, because it travels with the inventory. The same check then also
 * cancels the click unconditionally, so an unidentifiable or unexpected state can never be exploited
 * to move items out of a menu.
 *
 * <p>The menu also carries its page number, which is what makes pagination possible: the next-page
 * button opens a new menu with a higher page rather than mutating a shared view.
 *
 * <p>Server-thread confined, like everything in the interface.
 */
public final class XRayMenu implements InventoryHolder {

    private final String menuId;
    private final PlayerRef target;
    private final int page;
    private final Map<Integer, String> actionsBySlot = new HashMap<>();

    private Inventory inventory;

    /**
     * The action awaiting confirmation on a confirmation menu, or null.
     *
     * <p>Held on the menu rather than in a separate structure so that a moderator with two
     * confirmation menus somehow open cannot have them confuse one another.
     */
    private String pendingAction;

    public XRayMenu(String menuId, PlayerRef target, int page) {
        this.menuId = menuId;
        this.target = target;
        this.page = page;
    }

    public String menuId() {
        return menuId;
    }

    /** The player this menu concerns, or empty for menus that are not about one player. */
    public Optional<PlayerRef> target() {
        return Optional.ofNullable(target);
    }

    public int page() {
        return page;
    }

    /** Binds an action to a slot. */
    public void bind(int slot, String action) {
        actionsBySlot.put(slot, action);
    }

    /**
     * The action bound to a slot.
     *
     * <p>Only slots inside the top inventory can carry an action. A click in the player's own
     * inventory arrives with a raw slot beyond the menu's size, and must never resolve to something
     * the menu defined.
     */
    public Optional<String> actionAt(int rawSlot) {
        if (inventory != null && rawSlot >= inventory.getSize()) {
            return Optional.empty();
        }
        return Optional.ofNullable(actionsBySlot.get(rawSlot));
    }

    public String pendingAction() {
        return pendingAction;
    }

    public void pendingAction(String pendingAction) {
        this.pendingAction = pendingAction;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /** Called immediately after creation, because the holder must exist before the inventory does. */
    public void attach(Inventory inventory) {
        this.inventory = inventory;
    }
}
