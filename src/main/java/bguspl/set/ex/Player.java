package bguspl.set.ex;

import bguspl.set.Env;

import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

enum PlayerStatus {Penalty,Point,Continue, Terminated}

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */

/*
* when synchronizing on this - it is interaction between the current player and the dealer (wait, notify)
* when synchronizing on actions - it is for synchronizing the addition and removal of items from the list (blocking queue) of actions
* and avoiding the player thread to be active when he doesn't have anything to do (the action list is empty - we use: wait, notify).
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;

    //additional fields:
    private final Dealer dealer;
    public boolean ableToRun;
    private final BlockingQueue<Integer> actions;
    protected PlayerStatus status;
    private final long UNFREEZE = 0;
    private final long aiSleepBetweenKeypress = 1000; // ai waits a second between every two keypresses so it won't be so fast 

    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.dealer = dealer;
        this.table = table;
        this.id = id;
        this.human = human;
        this.terminate = false;
        this.score = 0;
        this.ableToRun = false;
        this.actions = new LinkedBlockingQueue<>(3);
    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        if (!human) createArtificialIntelligence();

        while (!terminate) {
            while (!ableToRun){ // dealer blocked the players from running
                try{
                    synchronized (dealer.playersLock){
                        dealer.playersLock.wait();
                    }
                } catch (InterruptedException ignored){}
            }

            synchronized (actions) {
                while ((!terminate) && actions.isEmpty()) { // there is nothing for the player to do
                    try {
                        actions.wait(); //if !human - aiThread keeps running and will wake the player
                    } catch (InterruptedException ignored) {}
                }
            }

            handleAction(); // the player has keypress to process - process the keypress
            handleFreeze(); //dealer is done checking the player's set - if there was any
        }
        if (!human) try { aiThread.join(); } catch (InterruptedException ignored) {}
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very, very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
            Random rnd = new Random();
            while (!terminate) {
                synchronized (aiThread){
                    while (!ableToRun){
                        try{
                            aiThread.wait();
                        }catch (InterruptedException ignored){}
                    }
                }

                int slot = rnd.nextInt(env.config.tableSize);
                keyPressed(slot);

                try{
                    aiThread.sleep(aiSleepBetweenKeypress);
                } catch(InterruptedException ignored){}
            }
            env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        terminate = true;
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        synchronized (actions) { //different lock than in the method run() because we need to limit access to actions - not wait for a condition to fulfill
            if (!terminate && ableToRun && status == PlayerStatus.Continue && table.slotToCard[slot] != null) { //will check amount of tokens in handleAction()
                while (true) {
                    try {
                        actions.put(table.slotToCard[slot]);
                        actions.notifyAll(); // notify the player thread that the action list is no longer empty and he has action to process
                        break;
                    } catch (InterruptedException ignored) {}
                }
            }
        }
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
        env.ui.setScore(id, ++score);
        env.ui.setFreeze(id,env.config.pointFreezeMillis); //freeze the player in the UI
        try{
            if(!human)
                aiThread.sleep(env.config.pointFreezeMillis);
            Thread.sleep((env.config.pointFreezeMillis));
        } catch (InterruptedException ignore){}
        status = PlayerStatus.Continue;
        env.ui.setFreeze(id, UNFREEZE); //release freezing in the UI
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        env.ui.setFreeze(id, env.config.penaltyFreezeMillis); //freeze the player in the UI
        try {
            if(!human)
                aiThread.sleep(env.config.penaltyFreezeMillis);
            Thread.sleep(env.config.penaltyFreezeMillis);
        } catch (InterruptedException ignored){}
        status = PlayerStatus.Continue;
        env.ui.setFreeze(id, UNFREEZE); //release freezing in the UI
    }

    public int score() { //getter for score
        return score;
    }

    // additional methods

    // the method polls an action from the actions list and performs it (add/remove token)
    private void handleAction(){
        Integer slot = null;
        synchronized (actions) {
            if (terminate || (!ableToRun) || actions.isEmpty()) // check conditions again just in case they have changed since last checked them
                return;

            try {
                slot = actions.take();
            } catch (InterruptedException ignored){}
        }

        if(table.slotToCard[slot] == null)
            return;

        if(table.hasToken(id, slot)){
            table.removeToken(id, slot);
        }
        else{
            int numOfTokens = table.getNumOfPlayersTokens(id);
            if(numOfTokens < Dealer.SET_SIZE) {
                table.placeToken(id, slot);
                numOfTokens++;

                if (numOfTokens == Dealer.SET_SIZE) {
                    ableToRun = false; //if !human - aiThread will be waiting too
                    dealer.addSetToCheck(id);

                    //wait for the dealer to check the set
                    try{
                        synchronized (this){
                            wait();
                        }
                    }catch (InterruptedException ignored){}
                }
            }
        }
    }

    private void handleFreeze(){
        if(status == PlayerStatus.Continue) //the player didn't have set (legal or otherwise)
            return;
        if(status == PlayerStatus.Point) //the player had a legal set
            point();
        else if(status == PlayerStatus.Penalty){ //the player's set wasn't legal
            penalty();
            if(!human)
                actions.clear();
        }
    }

    public synchronized void notifyPlayer(){
        ableToRun = true;
        if(!human)
            notifyAI();
        
        notifyAll();
    }

    public synchronized void suspendPlayer(){
        ableToRun = false;
    }

    public void notifyAI(){
        synchronized(aiThread){
            notifyAll();
        }
    }

    public void setPlayerThread(Thread playerThread){
        this.playerThread = playerThread;
    }

    public void clearPlayerActions(){
        actions.clear();
    }

    public boolean isHuman(){
        return this.human;
    }
}
