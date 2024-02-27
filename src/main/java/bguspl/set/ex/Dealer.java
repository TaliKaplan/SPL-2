package bguspl.set.ex;

import bguspl.set.Env;
import bguspl.set.ThreadLogger;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.concurrent.BlockingQueue;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;

    //additional fields:
    private final LinkedList<ThreadLogger> playersThreads;
    private final BlockingQueue<Integer> setsToCheck; //list of players (playerId) for which we need to check their sets
    boolean warn; //whether we need to paint timer in red
    private final LinkedList<Integer> cardsToRemove;
    public static int SET_SIZE; //answered from the forum: SetSize = config.featureSize
    private static final long wakeUpTime = 1000;
    private static final long fastWakeUp = 10;
    final Object playersLock; // is meant to use when we want to block/unblock all the players

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        terminate = false;

        //additional fields
        playersThreads = new LinkedList<ThreadLogger>();
        setsToCheck = new LinkedBlockingQueue<>();
        warn = false;
        cardsToRemove = new LinkedList<>();
        SET_SIZE = env.config.featureSize;
        playersLock = new Object();
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        //create players' threads
        for(int i = 0; i < players.length; i++){
            ThreadLogger player = new ThreadLogger(players[i], env.config.playerNames[i], env.logger);
            players[i].setPlayerThread(player);
            playersThreads.add(player);
            player.start();
        }

        //main loop of dealer
        while (!shouldFinish()) {
            Collections.shuffle(deck); //shuffle the cards in the deck
            placeCardsOnTable();
            ensureSetOnTable(); //make sure that there is a set on the table - deck must have at least 1 set - checked in shouldFinish()
            updateTimerDisplay(true);
            notifyPlayers();
            timerLoop(); //play for a minute before the dealer needs to reshuffle again
            suspendPlayers();
            clearPlayersActions(); //the cards are about to be changed - current players' actions are irrelevant
            removeAllCardsFromTable();
        }

        terminate();
        announceWinners();
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            sleepUntilWokenOrTimeout();
            updateTimerDisplay(false);
            checkSets();
            placeCardsOnTable();
        }
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        notifyPlayers();

        for(int i = 0; i < players.length; i++){
            players[i].terminate();
            synchronized (players[i]){ //in case the player waits for a keypress
                players[i].notifyAll();
            }
        }

        for(ThreadLogger playerThread: playersThreads){
            try {
                playerThread.joinWithLog();
            }catch (InterruptedException ignored){}
        }

        this.terminate = true;
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() { //if boolean terminate is true or there is no legal set left
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    /**
     * Checks cards should be removed from the table and removes them.
     * Those are cards that were part of a legal set
     */
    private void removeCardsFromTable() {
        for(int slot: cardsToRemove){
            table.removeCard(slot);
        }

        cardsToRemove.clear(); // already removed those cards, clear the list so next time we'll only remove was we need to remove.
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        //this is performed after reshuffling the deck
        int numToPlace = env.config.tableSize - table.countCards();

        for(int i = 0, j = 1; j < numToPlace && i < env.config.tableSize; i++){
            if(deck.isEmpty())
                break;

            if(table.slotToCard[i] == null){
                table.placeCard(deck.get(0), i);
                deck.remove(0);
                j++;
            }
        }

        // the UI action is performed in table.placeCard(card, slot) method - it is unnecessary to do it here.
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        synchronized (this){
            try{
                if(warn)
                    wait(fastWakeUp);
                else
                    wait(wakeUpTime);
            }
            // a player woke him up
            catch(InterruptedException ignored){}
        }
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        long timeLeft = reshuffleTime - System.currentTimeMillis();

        if(timeLeft < 0) // make sure timeLeft isn't negative
            timeLeft = 0;

        if(reset || timeLeft == 0)
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis; // reset reshuffleTime

        timeLeft = reshuffleTime - System.currentTimeMillis(); // how much time do we have until reshuffle
        if(timeLeft <= env.config.turnTimeoutMillis)
            warn = true;
        else warn = false;

        env.ui.setCountdown(timeLeft, warn);
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        for(int card: table.removeAllCards()){
            deck.add(card);
        }
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        int max = 0;
        LinkedList<Integer> winners = new LinkedList<Integer>();
        // Finds the max score and adds the winners
        for(Player player: players){
            if(player.score() > max){
                winners.clear();
                max = player.score();
                winners.add(player.id);
            }
            else if(player.score() == max)
                    winners.add(player.id);
        }

        int[] ids = new int[winners.size()];
        for(int i = 0; i < ids.length; i++){
            ids[i] = winners.get(i);
        }

        env.ui.announceWinner(ids);
    }

    // additional methods

    private void ensureSetOnTable(){
        List<Integer> cards = new LinkedList<>();
        for (Integer card: table.slotToCard) {
            if(card != null)
                cards.add(card);
        }

        while(env.util.findSets(cards, 1).isEmpty()){
            removeAllCardsFromTable();
            Collections.shuffle(deck);
            placeCardsOnTable();
        }
    }

    //Notifies all the players
    private void notifyPlayers(){
        for(Player p: players){
            p.ableToRun = true;
            if(!p.isHuman())
                p.notifyAI();
        }

        synchronized (playersLock){
            playersLock.notifyAll();
        }
    }

    //suspense all the players' run
    private void suspendPlayers(){
        for (Player p: players) {
            p.suspendPlayer();
        }
    }

    private void clearPlayersActions(){
        for (Player p: players){
            p.clearPlayerActions();
        }
    }


    //dealer checks if player/s have a set
    private void checkSets() {
        while (!setsToCheck.isEmpty()) {
            Integer playerId = setsToCheck.poll();
            Player player = players[playerId];
            int[] playerCards = table.getPlayersCards(playerId);

            if (playerCards.length != SET_SIZE) //check size of a set is legal
                player.status = PlayerStatus.Continue; //the player doesn't have a set - happens if several players tried to submit a set in the same time and this is the second player
            else {
                boolean isLegalSet = env.util.testSet(playerCards); //check if the set is legal

                if (isLegalSet) {
                    player.status = PlayerStatus.Point;

                    for (int card : playerCards) {
                        cardsToRemove.add(card);
                    }

                    removeCardsFromTable();

                } else player.status = PlayerStatus.Penalty;

                player.notifyPlayer(); //set is done being checked - player can continue now
            }
        }
    }


    // add a player to the list of sets that need to be checked
    public void addSetToCheck(int playerId){
        try {
            setsToCheck.put(playerId);
            synchronized (this){
                notifyAll(); //notify the dealer that there is a set to check
            }
        }catch (InterruptedException ignored){}
    }
}
