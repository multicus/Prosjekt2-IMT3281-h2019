package no.ntnu.imt3281.ludo.logic.messages;


public class ServerThrowDice extends Message {
    int ludoId;
    int diceRolled;

    public ServerThrowDice(String action){super(action);}

    public void setDiceRolled(int diceRolled) {
        this.diceRolled = diceRolled;
    }

    public int getDiceRolled() {
        return diceRolled;
    }

    public void setLudoId(int ludoId) {
        this.ludoId = ludoId;
    }

    public int getLudoId() {
        return ludoId;
    }

}

