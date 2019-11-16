package no.ntnu.imt3281.ludo.gui;

import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.event.Event;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Pane;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;
import javafx.util.Duration;
import no.ntnu.imt3281.ludo.client.ClientSocket;
import no.ntnu.imt3281.ludo.gui.ServerListeners.DiceThrowResponseListener;
import no.ntnu.imt3281.ludo.gui.ServerListeners.GameHasStartedResponseListener;
import no.ntnu.imt3281.ludo.gui.ServerListeners.PieceMovedResponseListener;
import no.ntnu.imt3281.ludo.gui.ServerListeners.UserLeftGameResponseListener;
import no.ntnu.imt3281.ludo.logic.*;
import no.ntnu.imt3281.ludo.logic.messages.*;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Arrays;

public class GameBoardController implements UserLeftGameResponseListener, GameHasStartedResponseListener, DiceThrowResponseListener,
        PieceMovedResponseListener,
        DiceListener, PieceListener, PlayerListener {

    @FXML
    private GridPane homeGrid;

    @FXML
    private ImageView redPiece0;

    @FXML
    private ImageView redPiece1;

    @FXML
    private ImageView redPiece2;

    @FXML
    private ImageView redPiece3;

    @FXML
    private ImageView bluePiece0;

    @FXML
    private ImageView bluePiece1;

    @FXML
    private ImageView bluePiece2;

    @FXML
    private ImageView bluePiece3;

    @FXML
    private ImageView yellowPiece0;

    @FXML
    private ImageView yellowPiece1;

    @FXML
    private ImageView yellowPiece2;

    @FXML
    private ImageView yellowPiece3;

    @FXML
    private ImageView greenPiece0;

    @FXML
    private ImageView greenPiece1;

    @FXML
    private ImageView greenPiece2;

    @FXML
    private ImageView greenPiece3;

    @FXML
    private GridPane movingGrid;

    @FXML
    private ImageView player1Active;

    @FXML
    private Label player1Name;

    @FXML
    private ImageView player2Active;

    @FXML
    private Label player2Name;

    @FXML
    private ImageView player3Active;

    @FXML
    private Label player3Name;

    @FXML
    private ImageView player4Active;

    @FXML
    private Label player4Name;

    @FXML
    private ImageView diceThrown;

    @FXML
    private Button throwTheDice;

    @FXML
    private TextArea chatArea;

    @FXML
    private TextField textToSay;

    @FXML
    private Button sendTextButton;

    @FXML
    private Pane winWindow;
    @FXML
    private Text winText;
    @FXML
    private ImageView winImage;

    // highlights to show players whose turn it is
    @FXML
    private Rectangle redHighlight;
    @FXML
    private Rectangle blueHighlight;
    @FXML
    private Rectangle yellowHighlight;
    @FXML
    private Rectangle greenHighlight;

    private ClientSocket clientSocket;
    private Ludo ludoGame;
    private String gameId;
    private String[] players = new String[]{};

    private Label[] playerNames;        // array that holds all players' labels. Indexing is done by Ludo players (RED, BLUE, YELLOW, GREEN)
    private ImageView[] redPieces;
    private ImageView[] bluePieces;
    private ImageView[] yellowPieces;
    private ImageView[] greenPieces;
    private ImageView[][] pieces;       // array that holds all pieces of a player. Indexing is done by ludo players (e.g. [0] = redPieces above)
    private Rectangle[] highlights;       // array that holds all highlight nodes. Indexing is done by Ludo players
    private Image[] diceImages;         // array that holds all dice images for animation while user rolls dice

    private int ourPlayerId;
    private ImageView[] ourPieces;      // same indexing as in Ludo logic class
    private int diceRolled = -1;        // -1 if dice has not been thrown by player yet, else 1-6
    private Timeline throwDiceAnim;

    @FXML
    public void initialize() {
        playerNames = new Label[]{player1Name, player2Name, player3Name, player4Name};
        redPieces = new ImageView[]{redPiece0, redPiece1, redPiece2, redPiece3};
        bluePieces = new ImageView[]{bluePiece0, bluePiece1, bluePiece2, bluePiece3};
        yellowPieces = new ImageView[]{yellowPiece0, yellowPiece1, yellowPiece2, yellowPiece3};
        greenPieces = new ImageView[]{greenPiece0, greenPiece1, greenPiece2, greenPiece3};
        pieces = new ImageView[][]{redPieces, bluePieces, yellowPieces, greenPieces};
        highlights = new Rectangle[]{redHighlight, blueHighlight, yellowHighlight, greenHighlight};
        diceImages = new Image[]{new Image("images/dice1.png"), new Image("images/dice2.png"), new Image("images/dice3.png"),
                new Image("images/dice4.png"), new Image("images/dice5.png"), new Image("images/dice6.png")};
    }

    /**
     * Setup necessary stuff for this object
     *
     * @param clientSocket for client-server communcation
     */
    public void setup(ClientSocket clientSocket, String gameId) {
        this.clientSocket = clientSocket;
        this.gameId = gameId;

        // initiate a new LudoGame object
        ludoGame = new Ludo();

        // add ludo listeners
        ludoGame.addDiceListener(this);
        ludoGame.addPieceListener(this);
        ludoGame.addPlayerListener(this);

        // add server listeners
        clientSocket.addUserLeftGameResponseListener(this);
        clientSocket.addGameHasStartedResponseListener(this);
        clientSocket.addDiceThrowResponseListener(this);
        clientSocket.addPieceMovedResponseListener(this);
    }

    /**
     * Add a list of players to the ludo game
     *
     * @param players the players in the game
     */
    public void setPlayers(String[] players) {
        // for each player that has not been added yet
        for (int i = this.players.length; i < players.length; i++) {
            ludoGame.addPlayer(players[i]);
        }

        // get our id of the ludogame
        ourPlayerId = ludoGame.getPlayerID(clientSocket.getDisplayName());
        System.out.println(ourPlayerId);


        // update our list here
        this.players = players;

        // set the text in GUI
        Platform.runLater(() -> {
            int playerCount = players.length;

            // set player texts and save our own pieces
            for (int i = 0; i < playerCount; i++) {
                playerNames[i].setText(players[i]);
                if (ourPlayerId == i) {
                    ourPieces = pieces[i];
                }
            }

            // make only our pieces selectable via mouse event (for GUI)
            for (Node node : ourPieces) {
                node.setMouseTransparent(false);
            }
        });
    }

    /**
     * When user clicks button to throw dice
     *
     * @param event
     */
    @FXML
    void throwDiceButton(ActionEvent event) {
        // disable button until we get response from server
        throwTheDice.setDisable(true);

        // send message to server that we want to throw dice
        clientSocket.sendMessageToServer(new UserDoesDiceThrow("UserDoesDiceThrow", ludoGame.activePlayer(), gameId));
    }

    /**
     * When user clicked anywhere on the grids to move a piece
     * <p>
     * Code inside here will only be executed if it's the player's turn
     * and the player clicked on his own piece.
     * </p>
     *
     * @param event
     */
    @FXML
    void clickedPiece(MouseEvent event) {
        // get the piece that was clicked on
        Node clickedNode = (Node) event.getPickResult().getIntersectedNode();
        int pieceId = -1;
        int localPiecePosition;
        int moveTo;
        boolean movePieceSuccess = false;

        // if we have not rolled dice yet or it's not our turn at the moment or we did not click on our own piece
        if (diceRolled == -1 || !itsMyTurn() || Arrays.stream(ourPieces).noneMatch(p -> p.getId().equals(clickedNode.getId()))) {
            return;
        }

        // get the pieceId we clicked on
        for (int i = 0; i < 4; i++) {
            if (clickedNode.equals(ourPieces[i])) {
                pieceId = i;
                break;
            }
        }
        // get the local position of the piece we clicked
        localPiecePosition = ludoGame.getPosition(ourPlayerId, pieceId);

        // player wants to move piece from home
        if (localPiecePosition == 0)
            moveTo = 1;
        else
            moveTo = localPiecePosition + diceRolled;

        // try to make the move that player chose
        movePieceSuccess = ludoGame.movePiece(ourPlayerId, localPiecePosition, moveTo);

        // could not move the piece for some reason
        if (!movePieceSuccess) {
            System.out.println("Could not move piece");
            return;
        }

        // if we rolled 6 and did not move piece from home => we get another throw
        if (diceRolled == 6 && localPiecePosition != 0) {
            throwTheDice.setDisable(false);
        }

        // we reset the dicerolled value (so we can't do illegal moves)
        diceRolled = -1;

        // we send a message to the server
        clientSocket.sendMessageToServer(new UserDoesPieceMove("UserDoesPieceMove", gameId, ourPlayerId, pieceId, localPiecePosition, moveTo));
    }


    @Override
    public void userLeftGameResponseEvent(UserLeftGameResponse response) {
        // remove from ludo logic
        ludoGame.removePlayer(response.getDisplayname());
        // todo handle chat as well
    }

    @Override
    public void gameHasStartedResponseEvent(GameHasStartedResponse response) {
        // enable button  and set highlight for the user whose turn it is (red automatically)
        if (itsMyTurn()) {
            throwTheDice.setDisable(false);
        }
        highlights[0].setVisible(true);
    }

    /**
     * Check if it is our turn
     *
     * @return
     */
    private boolean itsMyTurn() {
        return ludoGame.activePlayer() == ludoGame.getPlayerID(clientSocket.getDisplayName());
    }

    /**
     * When a dice has been thrown by us or another user
     *
     * @param response
     */
    @Override
    public void diceThrowResponseEvent(DiceThrowResponse response) {
        // throw the dice that was rolled by server
        ludoGame.throwDice(response.getDicerolled());

        // if we threw the dice and it's still our turn
        if (itsMyTurn()) {

            // if the player does not have all pieces at home, we let player move a piece
            for (int i = 0; i < 4; i++) {
                if (ludoGame.getPosition(ourPlayerId, i) != 0) {
                    diceRolled = response.getDicerolled();
                    return;
                }
            }

            // else all our pieces are still at home and we did not roll a six,
            // we re-enable the button
            if (response.getDicerolled() != 6) {
                throwTheDice.setDisable(false);
            } else {
                diceRolled = response.getDicerolled();
            }
        }
    }

    /**
     * The response when we or another user moved a piece.
     * <p>
     * We assume the values received are always correct
     * </p>
     *
     * @param response
     */
    @Override
    public void pieceMovedResponseEvent(PieceMovedResponse response) {
        // ignore messages if we were the ones who sent it out
        if (response.getPlayerid() == ourPlayerId) {
            return;
        }

        // else, register the move of another player into our logic class
        boolean success = ludoGame.movePiece(response.getPlayerid(), response.getMovedfrom(), response.getMovedto());

        // for debugging purposes
        if (!success) {
            try {
                ObjectMapper objectMapper = new ObjectMapper();
                String jsonMessage = objectMapper.writeValueAsString(response);  // json message to send
                System.out.println("This move in pieceMovedResponseEvent is not valid! Data received: " + jsonMessage);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Compare gameId in this object with gameId in parameter
     *
     * @param gameId the unique ID of the ludo game
     * @return if are equal or not
     */
    @Override
    public boolean equalsGameId(String gameId) {
        return this.gameId.equals(gameId);
    }

    /**
     * Called when the tab of this controller is closed.
     * Here we want to handle stuff like sending message to server.
     */
    public EventHandler<Event> onTabClose = new EventHandler<Event>() {
        @Override
        public void handle(Event arg0) {
            // remove listeners from clientsocket
            clientSocket.removeUserLeftGameResponseListener(GameBoardController.this);
            clientSocket.removeGameHasStartedResponseListener(GameBoardController.this);
            clientSocket.removeDiceThrowResponseListener(GameBoardController.this);
            clientSocket.removePieceMovedListener(GameBoardController.this);

            // disconnect user from the game
            clientSocket.sendMessageToServer(new UserLeftGame("UserLeftGame", gameId));
            // todo disconnect from the game chat as well
        }
    };

    /*
    LUDO GAME LISTENERS
     */

    @Override
    public void diceThrown(DiceEvent diceEvent) {
        Platform.runLater(() -> diceThrown.setImage(diceImages[diceEvent.getDiceRolled() - 1]));
    }

    /**
     * This event is called whenever a piece has been moved.
     * We use this to move the piece in the GUI.
     *
     * @param pieceEvent returns data about the piece moved
     */
    @Override
    public void pieceMoved(PieceEvent pieceEvent) {
        System.out.println("(Event) Moved piece " + pieceEvent.getPieceMoved() + " of player " + pieceEvent.getPlayerID() + " from " + pieceEvent.getFrom() + " to " + pieceEvent.getTo());
        // need to run code in main JavaFX thread
        Platform.runLater(() -> {
            int movedPlayerId = pieceEvent.getPlayerID();
            int movedPieceId = pieceEvent.getPieceMoved();
            int globalPositionFrom = ludoGame.userGridToLudoBoardGrid(movedPlayerId, pieceEvent.getFrom());      // convert player position to global
            int globalPositionTo = ludoGame.userGridToLudoBoardGrid(movedPlayerId, pieceEvent.getTo());
            GlobalToGridBoard.CellCoordinates gridPositionFrom = GlobalToGridBoard.globalToGridCoordinations(globalPositionFrom);   // convert global position to grid (GUI)
            GlobalToGridBoard.CellCoordinates gridPositionTo = GlobalToGridBoard.globalToGridCoordinations(globalPositionTo);

            if (pieceEvent.getFrom() == 0) {                                        // user moved from home position
                // change the from positions because we want to move the particular
                // piece the player clicked and need to convert localposition to the
                // correct gridpositions
                globalPositionFrom += movedPieceId;
                gridPositionFrom = GlobalToGridBoard.globalToGridCoordinations(globalPositionFrom);

                // get the piece this event was for
                Node piece = getNodeFromGridPane(homeGrid, gridPositionFrom.row, gridPositionFrom.column, movedPlayerId, movedPieceId);
                if (piece == null) {  // fixes a nasty bug when moving sometimes
                    System.out.println("(GUI) could not move piece because piece == null");
                    return;
                }

                homeGrid.getChildren().remove(piece);                       // remove from parent
                movingGrid.getChildren().add(piece);                        // add to new parent
                GridPane.setRowIndex(piece, gridPositionTo.row);            // set the specified row moved to
                GridPane.setColumnIndex(piece, gridPositionTo.column);      // set the specified column moved to
            } else if (pieceEvent.getFrom() != 0 && pieceEvent.getTo() == 0) {      // user lost a piece
                globalPositionTo += movedPieceId;
                gridPositionTo = GlobalToGridBoard.globalToGridCoordinations(globalPositionTo);

                Node piece = getNodeFromGridPane(movingGrid, gridPositionFrom.row, gridPositionFrom.column, movedPlayerId, movedPieceId);
                if (piece == null) {   // fixes a nasty bug when moving sometimes
                    System.out.println("(GUI) could not move piece because piece == null");
                    return;
                }

                movingGrid.getChildren().remove(piece);                     // remove from parent
                homeGrid.getChildren().add(piece);                          // add to new parent
                GridPane.setRowIndex(piece, gridPositionTo.row);            // set the specified row moved to
                GridPane.setColumnIndex(piece, gridPositionTo.column);      // set the specified column moved to
            } else {                                                                // all other board movements
                Node piece = getNodeFromGridPane(movingGrid, gridPositionFrom.row, gridPositionFrom.column, movedPlayerId, movedPieceId);
                if (piece == null) {  // fixes a nasty bug when moving sometimes
                    System.out.println("(GUI) could not move piece because piece == null");
                    return;
                }
                System.out.println(gridPositionFrom.row + ", " + gridPositionFrom.column + ", " + movedPieceId + ", " + movedPlayerId);
                System.out.println(piece);

                GridPane.setRowIndex(piece, gridPositionTo.row);            // set the specified row moved to
                GridPane.setColumnIndex(piece, gridPositionTo.column);      // set the specified column moved to
            }

            System.out.println("(GUI) Moved piece " + movedPieceId + " of player " + movedPlayerId + " to coordinates(" + gridPositionTo.row + ", " + gridPositionTo.column + ")");
        });
    }

    @Override
    public void playerStateChanged(PlayerEvent event) {
        final int playerId = event.getPlayerID();

        // user left game, let's disable his GUI for all other players
        if (event.getPlayerEvent().equals(PlayerEvent.LEFTGAME)) {
            Platform.runLater(() -> {
                String newPlayerName = ludoGame.getPlayerName(playerId);

                // make it clear to other player's in GUI that this player has left
                playerNames[playerId].setOpacity(0.25);
                playerNames[playerId].setText(newPlayerName);
            });
        }

        // when its another player's turn
        if (event.getPlayerEvent().equals(PlayerEvent.PLAYING)) {
            // highlight whose turn it is
            highlights[playerId].setVisible(true);

            // it's our turn again, enable throwdice button
            if (event.getPlayerID() == ourPlayerId) {
                System.out.println("Playing: Enable button");
                throwTheDice.setDisable(false);
            }
        }

        // when a player is done with his turn
        if (event.getPlayerEvent().equals(PlayerEvent.WAITING)) {
            // highlight whose turn it is
            highlights[playerId].setVisible(false);

            // our turn is done, disabling button
            if (event.getPlayerID() == ourPlayerId) {
                System.out.println("Waiting: Disable button");
                throwTheDice.setDisable(true);
            }
        }

        // a player won, so we disable further movements
        if (event.getPlayerEvent().equals(PlayerEvent.WON)) {
            Platform.runLater(() -> {
                winText.setText(players[playerId] + " Won!");
                winWindow.setVisible(true);
                winWindow.setDisable(false);
                throwTheDice.setDisable(true);
            });
        }
    }

    /**
     * Helper function to retrieve the Node in a specific cell in a grid.
     * <p>
     * Need to send playerId and movedPieceID to identify a piece to be moved if there are multiple
     * pieces in the same cell.
     * </p>
     *
     * @param gridPane the gridpane object we want to search in
     * @param row      the row of the gridpane
     * @param col      the column of the gridpane
     * @return the Node object of the found item in the cell or null if not found
     */
    private Node getNodeFromGridPane(GridPane gridPane, int row, int col, int playerId, int movedPieceId) {
        for (Node node : gridPane.getChildren()) {
            if (GridPane.getRowIndex(node) == row && GridPane.getColumnIndex(node) == col) {
                System.out.println(node.getId() + ", " + redPieces[movedPieceId].getId() + ", " + playerId);
                if ((node.getId().equals(redPieces[movedPieceId].getId()) && playerId == 0)      // here we get the
                        || (node.getId().equals(bluePieces[movedPieceId].getId()) && playerId == 1)     // correct pieces
                        || (node.getId().equals(yellowPieces[movedPieceId].getId()) && playerId == 2)   // of the players we
                        || (node.getId().equals(greenPieces[movedPieceId].getId()) && playerId == 3))   // want to get piece from
                    return node;
            }
        }
        return null;
    }
}
