package bguspl.set.ex;

import bguspl.set.Env;

import java.util.*;
import java.util.stream.Collectors;

/**
 * This class contains the data that is visible to the player.
 *
 * @inv slotToCard[x] == y iff cardToSlot[y] == x
 */
public class Table {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Mapping between a slot and the card placed in it (null if none).
     */
    protected final Integer[] slotToCard; // card per slot (if any)

    /**
     * Mapping between a card and the slot it is in (null if none).
     */
    protected final Integer[] cardToSlot; // slot per card (if any)


    // additional fields:

    // table - array of the slots in the table and for every slot a list of the players with token placed in the slot
    protected Vector<Vector<Integer>> table;

    private static final int cardNotFound = -1;

    /**
     * Constructor for testing.
     *
     * @param env        - the game environment objects.
     * @param slotToCard - mapping between a slot and the card placed in it (null if none).
     * @param cardToSlot - mapping between a card and the slot it is in (null if none).
     */
    public Table(Env env, Integer[] slotToCard, Integer[] cardToSlot) {

        this.env = env;
        this.slotToCard = slotToCard;
        this.cardToSlot = cardToSlot;

        table = new Vector<Vector<Integer>>();
        for(int i = 0; i < slotToCard.length; i++){
            table.add(new Vector<Integer>());
        }
    }

    /**
     * Constructor for actual usage.
     *
     * @param env - the game environment objects.
     */
    public Table(Env env) {

        this(env, new Integer[env.config.tableSize], new Integer[env.config.deckSize]);
    }

    /**
     * This method prints all possible legal sets of cards that are currently on the table.
     */
    public void hints() {
        List<Integer> deck = Arrays.stream(slotToCard).filter(Objects::nonNull).collect(Collectors.toList());
        env.util.findSets(deck, Integer.MAX_VALUE).forEach(set -> {
            StringBuilder sb = new StringBuilder().append("Hint: Set found: ");
            List<Integer> slots = Arrays.stream(set).mapToObj(card -> cardToSlot[card]).sorted().collect(Collectors.toList());
            int[][] features = env.util.cardsToFeatures(set);
            System.out.println(sb.append("slots: ").append(slots).append(" features: ").append(Arrays.deepToString(features)));
        });
    }

    /**
     * Count the number of cards currently on the table.
     *
     * @return - the number of cards on the table.
     */
    public int countCards() {
        int cards = 0;
        for (Integer card : slotToCard)
            if (card != null)
                ++cards;
        return cards;
    }

    /**
     * Places a card on the table in a grid slot.
     * @param card - the card id to place in the slot.
     * @param slot - the slot in which the card should be placed.
     *
     * @post - the card placed is on the table, in the assigned slot.
     */
    public void placeCard(int card, int slot) { //only dealer thread uses this method - making it thread safe is unnecessary.
        try {
            Thread.sleep(env.config.tableDelayMillis);
        } catch (InterruptedException ignored) {}

        cardToSlot[card] = slot;
        slotToCard[slot] = card;

        env.ui.placeCard(card, slot);
    }

    /**
     * Removes a card from a grid slot on the table.
     * @param slot - the slot from which to remove the card.
     */
    public void removeCard(int slot) { //only dealer thread uses this method - making it thread safe is unnecessary.
        try {
            Thread.sleep(env.config.tableDelayMillis);
        } catch (InterruptedException ignored) {}

        Integer card = slotToCard[slot];
        slotToCard[slot] = null;
        cardToSlot[card] = null;

        for(Integer playerId: table.get(slot)){ //remove all the tokens from the slot
            removeToken(playerId, slot);
        }

        env.ui.removeCard(slot);
    }

    /**
     * Places a player token on a grid slot.
     * @param player - the player the token belongs to.
     * @param slot   - the slot on which to place the token.
     */
    public void placeToken(int player, int slot) {
        if(slotToCard[slot] != null) { // slotToCard[slot] should never be null when trying to place a token
            table.get(slot).add(player);
            env.ui.placeToken(player, slot);
        }
    }

    /**
     * Removes a token of a player from a grid slot.
     * @param player - the player the token belongs to.
     * @param slot   - the slot from which to remove the token.
     * @return       - true iff a token was successfully removed.
     */
    public boolean removeToken(int player, int slot) {
        if(slotToCard[slot] == null)
            return false;
        table.get(slot).remove((Integer)player);
        env.ui.placeToken(player, slot);
        return true;
    }

    // additional methods for class Table

    // the method checks if the player has placed a token in the slot
    public boolean hasToken(int player, int slot){
        return table.get(slot).contains((Integer) player);
    }

    public int[] getPlayersCards(int playerId){
        int[] slots = new int[Dealer.SET_SIZE];
        Arrays.fill(slots, cardNotFound); //initialized for every element: slot[i] = cardNotFound

        int i = 0;
        int j = 0;

        for(Vector<Integer> slot: table){
            if(slot.contains((Integer) playerId)){
                slots[j] = slotToCard[i];
                j++;
            }
            i++;
        }
        return slots;
    }

    public int getNumOfPlayersTokens(int playerId){
        int[] cards = getPlayersCards(playerId);
        int size = 0;

        for(int i = 0; i < cards.length; i++){
            if(cards[i] != cardNotFound)
                size++;
        }

        return size;
    }

    public int[] removeAllCards(){
        int[] cards = new int[countCards()];
        int j = 0;

        for(int i = 0; i < slotToCard.length; i++){
            if(slotToCard[i] != null) {
                cards[j] = slotToCard[i];
                removeCard(i);
                j++;
            }
        }

        return cards;
    }
}
