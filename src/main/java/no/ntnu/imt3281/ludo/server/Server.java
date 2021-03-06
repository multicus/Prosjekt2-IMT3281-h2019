package no.ntnu.imt3281.ludo.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import no.ntnu.imt3281.ludo.logic.*;
import no.ntnu.imt3281.ludo.logic.messages.*;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * This is the main class for the server.
 */
public class Server implements DiceListener, PieceListener, PlayerListener {

	final private int SERVER_PORT = 4567; //Server Port
	private Database db; //Database

	private ArrayList<Ludo> activeLudoGames = new ArrayList<>(); //ArrayList of active games.
	private ArrayList<ChatRoom> activeChatRooms = new ArrayList<>(); //ArrayList of chat rooms.
	private ArrayList<Invitations> pendingInvites = new ArrayList<>(); //ArrayList of pending invites.

	private final LinkedList<Client> clients = new LinkedList<>(); //LinkedList containing clients

	private boolean stopping = false; //Boolean to stop the server

	private final ArrayBlockingQueue<Message> objectsToHandle = new ArrayBlockingQueue<>(100); //Queue for incoming messages

	private final ArrayBlockingQueue<Message> messagesToSend = new ArrayBlockingQueue<>(100); //Queue for outbound messages

	private final ArrayBlockingQueue<Client> disconnectedClients = new ArrayBlockingQueue<>(1000); //Queue for clients that is to be disconnected.

	/**
	 * Main function of the server.
	 * @param args arguments passed via cli
	 */
	public static void main(String[] args) {
		new Server(false); //Create a new server instance.
	}

	/**
	 * Server constructor.
	 * @param testing
	 * True means the server is used for tests. A test db is then used. This also disables pinging the clients.
	 * False is the standard way of running the server.
	 */
	public Server(boolean testing){
		startServerThread();
		startListener();
		startHandlingActions();
		startSenderThread();
		if (!testing){
			sendPingMessage();
			db = Database.getDatabase();
		} else {
			db = Database.constructTestDatabase("jdbc:derby:./ludoTestDB");
		}
		startRemoveDisconnectedClientsThread();

		System.out.println("Ludo server is now listening at 0.0.0.0:"+SERVER_PORT);

		try {
			db.createGlobalChatroom(); //Create global chatroom
		} catch (SQLException e) {
			e.printStackTrace();
		}

		setUpChatRooms();
		System.out.println("Chatrooms: " + activeChatRooms.toString());

	}

	/**
	 * Function for stopping the server.
	 */
	void stopServer(){
		stopping = true;
	}

	/**
	 * Creates chatroom objects with names from the db.
	 */
	private void setUpChatRooms(){
		ArrayList<String> roomNames = db.getAllChatRooms();
		for (String name : roomNames) {
			activeChatRooms.add(new ChatRoom(name));
		}
	}

	/**
	 *
	 * This one handles new connections to the server.
	 *
	 */
	private void startServerThread() {
		Thread server = new Thread(() -> {
			try {
				ServerSocket server1 = new ServerSocket(SERVER_PORT);
				while (!stopping) {
					Socket s = server1.accept();
					try {
						Client c = new Client(s);
						synchronized (clients) {
							clients.add(c);
						}
						System.out.println("Clients connceted: " + clients.size());
					} catch (IOException e) {
						System.err.println("Unable to create client from "+s.getInetAddress().getHostName());
					}
				}
			} catch ( IOException e) {
				e.printStackTrace();
			}
		});

		server.start();
	}


	/**
	 *
	 * This one Gets information from clients.
	 *
	 */
	private void startListener() {
		JsonMessageParser parser = new JsonMessageParser();
		TimerTask checkActivity = new TimerTask() {
			@Override
			public void run() {
				synchronized (clients) {
					Iterator<Client> iterator = clients.iterator();
					while (iterator.hasNext()) {
						Client c = iterator.next();
						try {
							String msg = c.read();
							if (msg != null && (msg.contains("UserDoesLogin") || msg.contains("UserDoesRegister"))) {
								synchronized (objectsToHandle) {
									c.parseSessionid(msg);
									System.out.println("Connected user : " + c.getUuid() + " " + msg);
									Message toBeQueued = parser.parseJson(msg,c.getUuid());
									if (toBeQueued != null) { //Discard if it is null
										objectsToHandle.add(parser.parseJson(msg,c.getUuid())); //Add the object to queue for handling
									} else {
										System.out.println("DISCARDED MESSAGE : " + msg);
									}
								}
							} else if (msg != null && c.getUuid() != null) {
								synchronized (objectsToHandle) {
									Message toBeQueued = parser.parseJson(msg,c.getUuid());
									if (toBeQueued != null) {
										objectsToHandle.add(parser.parseJson(msg,c.getUuid())); //Add the object to queue for handling
									} else {
										System.out.println("DISCARDED MESSAGE : " + msg);
									}
								}

							}
						} catch (IOException e) {   // Exception while reading from client, assume client is lost
							// Do nothing, this is really not likely to happen
						}
					}
				}
			}
		};
		Timer timer = new Timer();
		timer.scheduleAtFixedRate(checkActivity, 50L, 50L);
	}

	/**
	 * This sends data to the user
	 *
	 */
	private void startSenderThread() {
		Thread sender = new Thread(() -> {
			while (!stopping) {
				try {
					Message msg = messagesToSend.take();
					Iterator<Client> iterator = clients.iterator();
					while (iterator.hasNext()) {
						Client c = iterator.next();
						if ((c.getUuid() != null && msg.getRecipientSessionId() != null) && msg.getRecipientSessionId().contentEquals(c.getUuid())) {
							try {
								String converted = convertToCorrectJson(msg);
								System.out.println("Session id: " + c.getUuid() + " " + converted);
								c.send(converted);
							} catch (IOException e) {   // Exception while sending to client, assume client is lost
								synchronized (disconnectedClients) {
									if (!disconnectedClients.contains(c)) {
										disconnectedClients.add(c);
									}
								}
							}
						}
					}

				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		});
		sender.setDaemon(true);
		sender.start();
	}

	/**
	 * This removes users that has disconnected
	 */
	private void startRemoveDisconnectedClientsThread() {
		Thread removeDisconnectedClientsThread = new Thread(() -> {
			while (!stopping) {
				try {
					Client client = disconnectedClients.take();
					synchronized (clients) {
						clients.remove(client);
						removeClientsFromModules(client.getUserId());
						System.out.println("Clients connceted: " + clients.size());
					}
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		});
		removeDisconnectedClientsThread.setDaemon(true);
		removeDisconnectedClientsThread.start();
	}

	/**
	 * This is used to check if a client is still connected to the server.
	 */
	private void sendPingMessage(){
		Thread sendPingMessage = new Thread(() -> {
			while(!stopping) {
				LinkedList<Client> copyList = (LinkedList<Client>) clients.clone();
				Iterator<Client> clientIterator = copyList.iterator();
				while(clientIterator.hasNext()){
					Client c = clientIterator.next();
					try {
						c.send("{\"action\": \"Ping\"}");
					} catch (IOException e) {
						synchronized (clients) {
							disconnectedClients.add(c);
						}
					}
				}
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		});
		sendPingMessage.setDaemon(true);
		sendPingMessage.start();
	}

	/**
	 * This function removes a client from all chatrooms + games.
	 * @param userId String containing the user id of the user we want to remove from chat and games.
	 */
	private void removeClientsFromModules(String userId){
		//Remove user from chat rooms.
		ArrayList<ChatRoom> rooms = (ArrayList<ChatRoom>) activeChatRooms.clone();
			for(ChatRoom room : rooms){
				if(room.getConnectedUsers().contains(userId)){
					UserInfo info = db.getProfile(userId);
					announceRemovalToUsersInChatRoom(info, room.getName());
					removeUserFromChatroom(room.getName(), userId);
				}
			}

		//Remove user from ludo games.
		ArrayList<Ludo> games = (ArrayList<Ludo>) activeLudoGames.clone();
			UserInfo info = db.getProfile(userId);
			for(Ludo game : games) {
				if(game.getPlayerID(info.getDisplayName()) != -1) { //User is in this game. And there are more than 1 player active
					game.removePlayer(info.getDisplayName());
					UserLeftGameResponse retMsg = new UserLeftGameResponse("UserLeftGameResponse");
					retMsg.setDisplayname(info.getDisplayName());
					retMsg.setGameid(game.getGameid());

					for(String name : game.getActivePlayers()){
						if (!name.contentEquals(info.getDisplayName())){
							UserInfo userInfo = db.getProfilebyDisplayName(name);
							retMsg.setRecipientSessionId(useridToSessionId(userInfo.getUserId()));
							System.out.println(name);
							synchronized (messagesToSend){
								messagesToSend.add(retMsg);
							}
						}
					}



				}

				if(game.getActivePlayers().length < 1) { //Remove game since everyone has left.
					activeLudoGames.remove(game);
				}

			}

	}

	/**
	 * Thread to handle incoming messages from users.
	 */
	private void startHandlingActions(){
		Thread handleActions = new Thread(() -> {
			while (!stopping) {
				try {
					Message message = objectsToHandle.take();
					System.out.println("Handle obj : " + message.getAction());
					handleAction(message);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		});

		handleActions.start();
	}

	/**
	 *	Checks if the user is already logged in.
	 * @param userid userid of the user who is logging in.
	 * @return true if user is already logged in. False if user is not logged in
	 */
	private boolean alreadyLogged(String userid){

		Iterator<Client> iterator = clients.iterator();
		while(iterator.hasNext()){
			Client c = iterator.next();
			if (c.getUserId() != null && c.getUserId().contentEquals(userid)){
				return true;
			}
		}

		return false;
	}

	/**
	 * Determines what function is to be called by checking what action a message
	 * that comes from a client is.
	 * @param action Message received from user
	 */
	private void handleAction(Message action){
		switch (action.getAction()) {
			case "UserDoesDiceThrow": UserDoesDiceThrow((UserDoesDiceThrow) action);break;
			case "UserDoesLoginManual": UserDoesLoginManual((ClientLogin) action);break;
			case "UserDoesLoginAuto": UserDoesLoginAuto((ClientLogin) action); break;
			case "UserDoesRegister": UserDoesRegister((ClientRegister) action); break;
			case "UserJoinChat": UserJoinChat((UserJoinChat) action); break;
			case "UserSentMessage": UserSentMessage((UserSentMessage) action); break;
			case "UserLeftChatRoom": UserLeftChatRoom((UserLeftChatRoom) action); break;
			case "UserListChatrooms": UserListChatrooms((UserListChatrooms) action); break;
            case "UserWantsUsersList": UserWantsUsersList((UserWantsUsersList) action); break;
			case "UserWantsToCreateGame": UserWantsToCreateGame((UserWantsToCreateGame) action); break;
			case "UserDoesGameInvitationAnswer": UserDoesGameInvitationAnswer((UserDoesGameInvitationAnswer) action); break;
			case "UserLeftGame": UserLeftGame((UserLeftGame) action); break;
			case "UserDoesPieceMove" : UserDoesPieceMove((UserDoesPieceMove) action); break;
			case "UserDoesRandomGameSearch" : UserDoesRandomGameSearch((UserDoesRandomGameSearch) action);break;
			case "UserWantToViewProfile" : UserWantToViewProfile((UserWantToViewProfile) action); break;
			case "UserWantToEditProfile" : UserWantToEditProfile((UserWantToEditProfile) action); break;
			case "UserWantsLeaderboard" : UserWantsLeaderboard((UserWantsLeaderboard) action); break;
		}

	}

	/**
	 * This function converts from messages the server got and handled to a format
	 * the user can expect to receive.
	 * @param msg Message we want to convert to a String
	 * @return String json message
	 */
	private String convertToCorrectJson(Message msg) {
		ObjectMapper mapper = new ObjectMapper();
		try {
			String msgJson = mapper.writeValueAsString(msg);

			JsonNode jsonNode = mapper.readTree(msgJson);
			String action = jsonNode.get("action").asText();

			switch (action) {
				case "LoginResponse" :{
					LoginResponse message = new LoginResponse("LoginResponse");
					message.setLoginStatus(( (LoginResponse) msg) .isLoginStatus());
					message.setResponse(((LoginResponse) msg).getResponse());
					message.setUserid(((LoginResponse) msg).getUserid());
					message.setDisplayname(((LoginResponse)msg).getDisplayname());
					return mapper.writeValueAsString(message);
				}
				case "RegisterResponse": {
					RegisterResponse message = new RegisterResponse("RegisterResponse");
					message.setRegisterStatus(( (RegisterResponse) msg) .isRegisterStatus());
					message.setResponse(((RegisterResponse) msg).getResponse());
					return mapper.writeValueAsString(message);
				}
				case "DiceThrowResponse" : {
					DiceThrowResponse message = new DiceThrowResponse("DiceThrowResponse");
					message.setDicerolled(((DiceThrowResponse)msg).getDicerolled());
					message.setGameid(((DiceThrowResponse)msg).getGameid());
					return mapper.writeValueAsString(message);
				}
				case "ChatJoinNewUserResponse" : {
					ChatJoinNewUserResponse message = new ChatJoinNewUserResponse("ChatJoinNewUserResponse");
					message.setDisplayname(((ChatJoinNewUserResponse)msg).getDisplayname());
					message.setChatroomname(((ChatJoinNewUserResponse)msg).getChatroomname());
					return mapper.writeValueAsString(message);
				}
				case "ChatJoinResponse" : {
					ChatJoinResponse message = new ChatJoinResponse("ChatJoinResponse");
					message.setStatus(((ChatJoinResponse)msg).isStatus());
					message.setResponse(((ChatJoinResponse)msg).getResponse());
					message.setChatroomname(((ChatJoinResponse)msg).getChatroomname());
					message.setUsersinroom(((ChatJoinResponse)msg).getUsersinroom());
					message.setChatlog(((ChatJoinResponse)msg).getChatlog());
					return mapper.writeValueAsString(message);
				}
				case "SentMessageResponse": {
					SentMessageResponse message = new SentMessageResponse("SentMessageResponse");
					message.setdisplayname(((SentMessageResponse)msg).getdisplayname());
					message.setChatroomname(((SentMessageResponse)msg).getChatroomname());
					message.setChatmessage(((SentMessageResponse)msg).getChatmessage());
					message.setTimestamp(((SentMessageResponse)msg).getTimestamp());
					return mapper.writeValueAsString(message);
				}
				case "UserLeftChatRoomResponse": {
					UserLeftChatRoomResponse message = new UserLeftChatRoomResponse("UserLeftChatRoomResponse");
					message.setDisplayname(((UserLeftChatRoomResponse)msg).getDisplayname());
					message.setChatroomname(((UserLeftChatRoomResponse)msg).getChatroomname());
					return mapper.writeValueAsString(message);
				}
				case "ErrorMessageResponse" : {
					ErrorMessageResponse message = new ErrorMessageResponse("ErrorMessageResponse");
					message.setMessage(((ErrorMessageResponse)msg).getMessage());
					return mapper.writeValueAsString(message);
				}
				case "ChatRoomsListResponse" : {
					ChatRoomsListResponse message = new ChatRoomsListResponse("ChatRoomsListResponse");
					message.setChatRoom(((ChatRoomsListResponse)msg).getChatRoom());
					return mapper.writeValueAsString(message);
				}
                case "UsersListResponse" : {
                    UsersListResponse message = new UsersListResponse("UsersListResponse");
                    message.setDisplaynames(((UsersListResponse)msg).getDisplaynames());
					return mapper.writeValueAsString(message);
                }
				case "CreateGameResponse": {
					CreateGameResponse message = new CreateGameResponse("CreateGameResponse");
					message.setGameid(((CreateGameResponse)msg).getGameid());
					message.setJoinstatus(((CreateGameResponse)msg).isJoinstatus());
					message.setResponse(((CreateGameResponse)msg).getResponse());
					return mapper.writeValueAsString(message);
				}
				case "SendGameInvitationsResponse": {
					SendGameInvitationsResponse message = new SendGameInvitationsResponse("SendGameInvitationsResponse");
					message.setHostdisplayname(((SendGameInvitationsResponse)msg).getHostdisplayname());
					message.setGameid(((SendGameInvitationsResponse)msg).getGameid());
					return mapper.writeValueAsString(message);
				}
				case "UserJoinedGameResponse": {
					UserJoinedGameResponse message = new UserJoinedGameResponse("UserJoinedGameResponse");
					message.setPlayersinlobby(((UserJoinedGameResponse)msg).getPlayersinlobby());
					message.setUserid(((UserJoinedGameResponse)msg).getUserid());
					message.setGameid(((UserJoinedGameResponse)msg).getGameid());
					return mapper.writeValueAsString(message);
				}
				case "UserDeclinedGameInvitationResponse":{
					UserDeclinedGameInvitationResponse message = new UserDeclinedGameInvitationResponse("UserDeclinedGameInvitationResponse");
					message.setUserid(((UserDeclinedGameInvitationResponse)msg).getUserid());
					message.setGameid(((UserDeclinedGameInvitationResponse)msg).getGameid());
					return mapper.writeValueAsString(message);
				}
				case "UserLeftGameResponse":{
					UserLeftGameResponse message = new UserLeftGameResponse("UserLeftGameResponse");
					message.setGameid(((UserLeftGameResponse)msg).getGameid());
					message.setDisplayname(((UserLeftGameResponse)msg).getDisplayname());
					return mapper.writeValueAsString(message);
				}
				case "PieceMovedResponse": {
					PieceMovedResponse message = new PieceMovedResponse("PieceMovedResponse");
					message.setPlayerid(((PieceMovedResponse)msg).getPlayerid());
					message.setPiecemoved(((PieceMovedResponse)msg).getPiecemoved());
					message.setMovedto(((PieceMovedResponse)msg).getMovedto());
					message.setMovedfrom(((PieceMovedResponse)msg).getMovedfrom());
					message.setGameid(((PieceMovedResponse)msg).getGameid());
					return mapper.writeValueAsString(message);
				}
				case "PlayerWonGameResponse":{
					PlayerWonGameResponse message = new PlayerWonGameResponse("PlayerWonGameResponse");
					message.setPlayerwonid(((PlayerWonGameResponse)msg).getPlayerwonid());
					message.setGameid(((PlayerWonGameResponse)msg).getGameid());
					return mapper.writeValueAsString(message);
				}
				case "GameHasStartedResponse":{
					GameHasStartedResponse message = new GameHasStartedResponse("GameHasStartedResponse");
					message.setGameid(((GameHasStartedResponse)msg).getGameid());
					return mapper.writeValueAsString(message);
				}
				case "UserWantToViewProfileResponse":{
					UserWantToViewProfileResponse message = new UserWantToViewProfileResponse("UserWantToViewProfileResponse");
					message.setUserId(((UserWantToViewProfileResponse)msg).getUserId());
					message.setGamesWon(((UserWantToViewProfileResponse)msg).getGamesWon());
					message.setGamesPlayed(((UserWantToViewProfileResponse)msg).getGamesPlayed());
					message.setDisplayName(((UserWantToViewProfileResponse)msg).getDisplayName());
					message.setImageString(((UserWantToViewProfileResponse)msg).getImageString());
					message.setMessage(((UserWantToViewProfileResponse)msg).getMessage());
					return mapper.writeValueAsString(message);
				}
				case "UserWantToEditProfileResponse":{
					UserWantToEditProfileResponse message = new UserWantToEditProfileResponse("UserWantToEditProfileResponse");
					message.setResponse(((UserWantToEditProfileResponse)msg).getResponse());
					message.setChanged(((UserWantToEditProfileResponse)msg).isChanged());
					message.setDisplayname(((UserWantToEditProfileResponse)msg).getDisplayname());
					return mapper.writeValueAsString(message);
				}
				case "LeaderboardResponse":{
					LeaderboardResponse message = new LeaderboardResponse("LeaderboardResponse");
					message.setToptenplays(((LeaderboardResponse)msg).getToptenplays());
					message.setToptenwins(((LeaderboardResponse)msg).getToptenwins());
					return mapper.writeValueAsString(message);
				}

				default: {
					System.out.println(msg);
					return "{\"ERROR\":\"server.genericError\"}";
				}
			}

		} catch (IOException e) {
			e.printStackTrace();
		}

		return null;
	}

	/**
	 * When the user logs in without using the remember me function.
	 * @param action ClientLogin message from user
	 */
	private void UserDoesLoginManual(ClientLogin action){
		LoginResponse retMsg = new LoginResponse("LoginResponse");
		retMsg.setRecipientSessionId(action.getRecipientSessionId());
		try {
			boolean status = db.checkIfLoginValid(action.getUsername(), action.getPassword());
			retMsg.setLoginStatus(status);

			if(retMsg.isLoginStatus()){ //If login was successful we set the userid on the client.
				retMsg.setResponse("server.loginOk");

				String userid = db.getUserId(action.getUsername());
				if (!alreadyLogged(userid)){
					retMsg.setUserid(userid);
					setUseridToClient(action.getRecipientSessionId(), userid);
					UserInfo info = db.getProfile(userid);
					retMsg.setDisplayname(info.getDisplayName());

					int tokenCount = db.countSessionToken(userid);

					if (tokenCount > 0) { //Terminate existing token before inserting the new one.
						db.terminateSessionToken(userid);
						db.insertSessionToken(action.getRecipientSessionId(), userid);
					} else {
						db.insertSessionToken(action.getRecipientSessionId(), userid);
					}
				} else {
					retMsg.setResponse("server.loginAlready");
					retMsg.setLoginStatus(false);
				}

			} else {
				retMsg.setResponse("server.loginFail");
			}

		} catch (SQLException e) {
			retMsg.setResponse("server.internalError");
			retMsg.setLoginStatus(false);
			e.printStackTrace();
		}

		synchronized (messagesToSend) {
			messagesToSend.add(retMsg);
		}

	}

	/**
	 * When a user logs in automatically using a remember me function.
	 * @param action ClientLogin message from user
	 */
	private void UserDoesLoginAuto(ClientLogin action){

		LoginResponse retMsg = new LoginResponse("LoginResponse");
		retMsg.setRecipientSessionId(action.getRecipientSessionId());

		try {
			boolean status = db.checkIfLoginValid(action.getRecipientSessionId());
			retMsg.setLoginStatus(status);
			if(status) {
				retMsg.setResponse("server.loginOk");
				String userid = db.getUserIdBySession(retMsg.getRecipientSessionId());
				if (!alreadyLogged(userid)){
					retMsg.setUserid(userid);
					setUseridToClient(action.getRecipientSessionId(), userid);
					UserInfo info = db.getProfile(userid);
					retMsg.setDisplayname(info.getDisplayName());
				} else {
					retMsg.setResponse("server.loginAlready");
					retMsg.setLoginStatus(false);
				}

			} else {
				retMsg.setResponse("server.invalidToken");
			}

		} catch (SQLException e) {
			retMsg.setLoginStatus(false);
			retMsg.setResponse("server.internalError");
			e.printStackTrace();
		}

		synchronized (messagesToSend) {
			messagesToSend.add(retMsg);
		}

	}

	/**
	 * When a user wants to list all chat rooms.
	 * @param action UserListChatrooms message from user
	 */
	private void UserListChatrooms(UserListChatrooms action) {
		ChatRoomsListResponse retMsg = new ChatRoomsListResponse("ChatRoomsListResponse");
		retMsg.setRecipientSessionId(action.getRecipientSessionId());

		ArrayList<String> roomNames = new ArrayList<String>();
		for(ChatRoom room : activeChatRooms) {
			if (!room.isGameRoom()) {
				roomNames.add(room.getName());
			}
		}

		String[] arr = new String[roomNames.size()];
		for(int i = 0; i < roomNames.size(); i++) {
			arr[i] = roomNames.get(i);
		}

		retMsg.setChatRoom(arr);

		synchronized (messagesToSend) {
			messagesToSend.add(retMsg);
		}

	}
	/**
	 * Logic for when a client want to register an account.
	 * @param action ClientRegister message from user
	 */
	private void UserDoesRegister(ClientRegister action){

		RegisterResponse retMsg = new RegisterResponse("RegisterResponse");
		retMsg.setRecipientSessionId(action.getRecipientSessionId());

		/* Check if the username already exists */

		try {
			boolean usernameExists = db.doesAccountNameExist(action.getUsername());
			if (!usernameExists) {
				db.insertAccount(action.getUsername(), action.getPassword());
				retMsg.setRegisterStatus(true);
				retMsg.setResponse("server.registerOk");
			} else {
				retMsg.setRegisterStatus(false);
				retMsg.setResponse("server.registerFail");
			}

		} catch (SQLException e) {
			retMsg.setRegisterStatus(false);
			retMsg.setResponse("server.internalError");
			e.printStackTrace();
		}
		synchronized (messagesToSend) {
			messagesToSend.add(retMsg);
		}

	}

	/**
	 * When a user wants to join a chat room
	 * @param action UserJoinChat message from user
	 */
	private void UserJoinChat(UserJoinChat action) {
		//Security check.
		boolean secCheckPass = securityCheck(action.getUserid(),action.getRecipientSessionId());
		if(!secCheckPass){
			System.out.println("Warning: " + action.getUserid() + " tried to Join a Chat, but " + action.getRecipientSessionId() + " does not match...");
			return;
		}

		ChatJoinResponse retMsg = new ChatJoinResponse("ChatJoinResponse");
		retMsg.setRecipientSessionId(useridToSessionId(action.getUserid()));

		if (chatRoomExists(action.getChatroomname())) {

			if (chatRoomIsGameOnly(action.getChatroomname())) {
				UserInfo info = db.getProfile(action.getUserid());
				if (!userIsAllowedInRoom(action.getChatroomname(), info.getDisplayName())){
					retMsg.setResponse("server.roomNotAllowed");
					retMsg.setChatroomname(action.getChatroomname());
					retMsg.setStatus(false);
					synchronized (messagesToSend) {
						messagesToSend.add(retMsg);
						return; //We dont do anything else here.
					}
				}
			}

			boolean added = addUserToChatroom(action.getChatroomname(), action.getUserid());

			retMsg.setStatus(added);
			retMsg.setChatroomname(action.getChatroomname());

			if (added) {
				retMsg.setResponse("server.roomJoinOk");

				retMsg.setUsersinroom(getUsersInChatRoom(action.getChatroomname()));
				retMsg.setChatlog(getChatLog(action.getChatroomname()));

				//Announce the users presence to others in the chat room.
				announceToUsersInChatRoom(retMsg, action.getChatroomname());

			} else {
				retMsg.setResponse("server.roomJoinFail");
			}

		} else { //Create chatroom and join it.

			try {
				db.insertChatRoom(action.getChatroomname());
				boolean created = db.isChatRoom(action.getChatroomname());
				if (created) {
					ChatRoom room = new ChatRoom(action.getChatroomname());
					room.getConnectedUsers().add(action.getUserid());
					activeChatRooms.add(room);

					retMsg.setStatus(true);
					retMsg.setResponse("server.roomCreateOk");
					retMsg.setChatroomname(action.getChatroomname());

					//Announce the users presence to others in the chat room.
					announceToUsersInChatRoom(retMsg, action.getChatroomname());

					retMsg.setUsersinroom(getUsersInChatRoom(action.getChatroomname()));
					retMsg.setChatlog(getChatLog(action.getChatroomname()));

				} else {
					retMsg.setStatus(false);
					retMsg.setResponse("server.roomCreateFail");
					retMsg.setChatroomname(action.getChatroomname());
				}

			} catch (SQLException e) {
				e.printStackTrace();
				retMsg.setStatus(false);
				retMsg.setResponse("server.internalError");
				retMsg.setChatroomname(action.getChatroomname());
			}

		}

		synchronized (messagesToSend) {
			messagesToSend.add(retMsg);
		}

	}

	/**
	 *
	 * @param chatroomname String with the name of the chatroom
	 * @return Array of chat messages in a chat room.
	 */
	private ChatMessage[] getChatLog(String chatroomname){
		ArrayList<ChatMessage> arraylist = db.getChatMessages(chatroomname);

		// fixing NullPointerException when no chat history is available
		if(arraylist == null){
			return new ChatMessage[]{};
		}

		ChatMessage[] arr;

		if  (arraylist.size() >= 50) {
			List<ChatMessage> subArraylist = arraylist.subList(arraylist.size()-50, arraylist.size());
			arr = new ChatMessage[subArraylist.size() +1];
			for (int i = 0; i <= subArraylist.size(); i++){
				arr[i] = arraylist.get(i);
			}
		} else {
			arr = new ChatMessage[arraylist.size()];
			for (int i = 0; i < arraylist.size(); i++){
				arr[i] = arraylist.get(i);
			}
		}

		return arr;
	}

	/**
	 * When a user leaves a chat room
	 * @param action UserLeftChatRoom message from user
	 */
	private void UserLeftChatRoom(UserLeftChatRoom action){
		Message retMsg;
		String recipientId = useridToSessionId(action.getUserid());
		System.out.println(recipientId);

		if (userIsInChatroom(action.getChatroomname(),action.getUserid())) {
			boolean removed = removeUserFromChatroom(action.getChatroomname(), action.getUserid());

			if (removed) {
				retMsg = new UserLeftChatRoomResponse("UserLeftChatRoomResponse");
				retMsg.setRecipientSessionId(recipientId);
				((UserLeftChatRoomResponse)retMsg).setChatroomname(action.getChatroomname());

				UserInfo info = db.getProfile(action.getUserid());

				((UserLeftChatRoomResponse)retMsg).setDisplayname(info.getDisplayName());

				announceRemovalToUsersInChatRoom(info, action.getChatroomname());
			} else {
				retMsg = new ErrorMessageResponse("ErrorMessageResponse");
				retMsg.setRecipientSessionId(recipientId);
				((ErrorMessageResponse)retMsg).setMessage("server.roomLeaveFail");
			}

		} else {
			retMsg = new ErrorMessageResponse("ErrorMessageResponse");
			retMsg.setRecipientSessionId(recipientId);
			((ErrorMessageResponse)retMsg).setMessage("server.roomLeaveError");
		}

		synchronized (messagesToSend) {
			messagesToSend.add(retMsg);
		}

	}

	/**
	 * When a user wants to send a message to a chat room.
	 * @param action UserSentMessage message from user
	 */
	private void UserSentMessage(UserSentMessage action) {
		//Security check.
		boolean secCheckPass = securityCheck(action.getUserid(),action.getRecipientSessionId());
		if(!secCheckPass){
			System.out.println("Warning: " + action.getUserid() + " tried to send a message, but " + action.getRecipientSessionId() + " does not match...");
			return;
		}

		Message retMsg = new SentMessageResponse("SentMessageResponse");

		boolean isConnected, roomExists;

		isConnected = userIsInChatroom(action.getChatroomname(), action.getUserid());
		roomExists = chatRoomExists(action.getChatroomname());

		if (roomExists && isConnected){
			try {
				db.insertChatMessage(action.getChatroomname(), action.getUserid(), action.getChatmessage());
				UserInfo info = db.getProfile(action.getUserid());
				((SentMessageResponse)retMsg).setdisplayname(info.getDisplayName());
				((SentMessageResponse)retMsg).setChatroomname(action.getChatroomname());
				((SentMessageResponse)retMsg).setChatmessage(action.getChatmessage());
				((SentMessageResponse)retMsg).setTimestamp(String.valueOf(Instant.now().getEpochSecond()));

				sendMessageToChatRoom(retMsg, ((SentMessageResponse) retMsg).getChatroomname());

			} catch (SQLException e) {
				e.printStackTrace();
			}
		} else { //If the chatroom is non existent give the user a error message.
			retMsg = new ErrorMessageResponse("ErrorMessageResponse");
			if (!isConnected) {
				((ErrorMessageResponse)retMsg).setMessage("server.messageNotInRoom");
			}

			if (!roomExists) {
				((ErrorMessageResponse)retMsg).setMessage("server.messageNoRoom");
			}

			retMsg.setRecipientSessionId(useridToSessionId(action.getUserid()));

			synchronized (messagesToSend) {
				messagesToSend.add(retMsg);
			}
		}


	}

	/**
	 * When a user throws a dice.
	 * @param action UserDoesDiceThrow from user
	 */
	private void UserDoesDiceThrow(UserDoesDiceThrow action){
		for(Ludo game : activeLudoGames) {
			if (game.getGameid().contentEquals(action.getGameid())) {
				game.throwDice();
			}
		}
	}

	/**
	 * When a user does a piece move.
	 * @param action UserDoesPieceMove from user
	 */
	private void UserDoesPieceMove(UserDoesPieceMove action){
		for (Ludo game : activeLudoGames) {
			if (game.getGameid().contentEquals(action.getGameid())){
				game.movePiece(action.getPlayerid(), action.getMovedfrom(), action.getMovedto());
			}
		}
	}

	/**
	 * Converts session UUID to userid
	 * @param sessionId String containing session id we want to convert to user id
	 * @return userid user id associated with the session id
	 */
	private String sessionIdToUserId(String sessionId){
		Iterator<Client> c = clients.iterator();
		while(c.hasNext()) {
			Client client = c.next();
			if (client.getUuid().contentEquals(sessionId)) {
				return client.getUserId();
			}
		}

		return null;
	}

	/**
	 * Converts userid to sessionid
	 * @param userid String containing user id we want to convert to session id
	 * @return sessionid session id associated with the user id
	 */
	private String useridToSessionId(String userid){
		Iterator<Client> c = clients.iterator();
		while(c.hasNext()) {
			Client client = c.next();
			if (client.getUserId() != null && client.getUserId().contentEquals(userid)) {
				return client.getUuid();
			}
		}

		return null;
	}

	/**
	 * Sets userid to client
	 * @param sessionId String containing session id.
	 * @param userid String containing user id.
	 */
	private void setUseridToClient(String sessionId , String userid){
		Iterator<Client> c = clients.iterator();
		while(c.hasNext()) {
			Client client = c.next();
			if (client.getUuid().contentEquals(sessionId)) {
				client.setUserId(userid);
				return;
			}
		}
	}

	/**
	 * Adds a user to the chatroom
	 * @param chatRoomName String containing chatRoomName
	 * @param userid String containing user id
	 * @return true if user was added to chat room, false if not
	 */
	private boolean addUserToChatroom(String chatRoomName, String userid){
		for(ChatRoom room : activeChatRooms) {
			if (room.getName().toLowerCase().contentEquals(chatRoomName.toLowerCase())) {
				if (!room.connectedUsers.contains(userid)) {
					room.connectedUsers.add(userid);
					return true;
				} else {
					return false;
				}
			}
		}
		return false;
	}

	/**
	 * Is the user in a specific chat room
	 * @param chatRoomName String containing chat room name
	 * @param userid String containing user id
	 * @return true if a user is in the requested chat room, false if user is not in requested chat room.
	 */
	private boolean userIsInChatroom(String chatRoomName, String userid) {
		for(ChatRoom room : activeChatRooms) {
			if (room.getName().toLowerCase().contentEquals(chatRoomName.toLowerCase())) {
				if (room.connectedUsers.contains(userid)) {
					return true;
				} else {
					return false;
				}
			}
		}
		return false;
	}

	/**
	 * Get a string array of display names.
	 * @param chatRoomName String containing chat room name
	 * @return string array of display names in a chat room
	 */
	private String[] getUsersInChatRoom(String chatRoomName){
		String[] arr = new String[0];
		ArrayList<String> arrayList = null;

		for(ChatRoom room : activeChatRooms) {
			if (room.getName().toLowerCase().contentEquals(chatRoomName.toLowerCase())){
				arrayList = new ArrayList<>();
				for (String userid : room.getConnectedUsers()){
					UserInfo info = db.getProfile(userid);
					arrayList.add(info.getDisplayName());
				}
			}
		}

		if (arrayList.size() > 0) {
			arr = new String[arrayList.size()];
			for ( int i = 0; i < arrayList.size(); i++) {
				arr[i] = arrayList.get(i);
			}
		}

		return arr;
	}

	/**
	 * Send chat message to a chat room
	 * @param action Message object
	 * @param chatroom String containing chat room name
	 */
	private void sendMessageToChatRoom(Message action, String chatroom){

		for (ChatRoom room : activeChatRooms) { //Loop over chat rooms
			if (room.getName().contentEquals(chatroom)){ // Find correct chat room
				for(String UserId : room.getConnectedUsers()){ //Get all active users
					SentMessageResponse sentMessageResponse = new SentMessageResponse("SentMessageResponse");
					sentMessageResponse.setChatmessage(((SentMessageResponse)action).getChatmessage());
					sentMessageResponse.setChatroomname(chatroom);
					sentMessageResponse.setdisplayname(((SentMessageResponse)action).getdisplayname());
					sentMessageResponse.setTimestamp(((SentMessageResponse)action).getTimestamp());
					sentMessageResponse.setRecipientSessionId(useridToSessionId(UserId));

					synchronized (messagesToSend) {
						messagesToSend.add(sentMessageResponse); //Send message.
					}
				}
				return;
			}
		}

	}

	/**
	 * Announces that a user has entered their chat room
	 * @param action Message object
	 * @param chatroomname String containing chat room name
	 */
	private void announceToUsersInChatRoom(Message action, String chatroomname){

		UserInfo info = db.getProfile(sessionIdToUserId(action.getRecipientSessionId()));

		for (ChatRoom room : activeChatRooms) { //Loop over chat rooms
			if (room.getName().contentEquals(chatroomname)){ // Find correct chat room
				for(String UserId : room.getConnectedUsers()){ //Get all active users
					if (!UserId.contentEquals(sessionIdToUserId(action.getRecipientSessionId()))) {
						ChatJoinNewUserResponse chatJoinNewUserResponse = new ChatJoinNewUserResponse("ChatJoinNewUserResponse");
						chatJoinNewUserResponse.setDisplayname(info.getDisplayName());
						chatJoinNewUserResponse.setChatroomname(room.getName());
						chatJoinNewUserResponse.setRecipientSessionId(useridToSessionId(UserId));

						synchronized (messagesToSend) {
							messagesToSend.add(chatJoinNewUserResponse); //Send message.
						}
					}
				}
				return;
			}
		}
	}

	/**
	 * Removes a user from a chatroom
	 * @param chatRoomName String containing chat room name
	 * @param userid String containing user id
	 * @return true if user was removed from chat room, false if not
	 */
	private boolean removeUserFromChatroom(String chatRoomName, String userid) {
		for(ChatRoom room : activeChatRooms) {
			if (room.getName().toLowerCase().contentEquals(chatRoomName.toLowerCase())) {
				if (room.connectedUsers.contains(userid)) {
					room.connectedUsers.remove(userid);

					if (room.getConnectedUsers().size() == 0 && !room.getName().toLowerCase().contentEquals("global") ) { //Delete it
						activeChatRooms.remove(room);
						try {
							db.removeChatRoom(room.getName()); //Remove from DB.
						} catch (SQLException e) {
							e.printStackTrace();
						}
					}

					return true;
				} else {
					return false;
				}
			}
		}
		return false;
	}

	/**
	 * Announce the removal of a user in a chat room to other users in the same chat room
	 * @param info UserInfo object containing info about the user that is to be announced
	 * @param chatroomname String containing chat room name
	 */
	private void announceRemovalToUsersInChatRoom(UserInfo info, String chatroomname){

		for (ChatRoom room : activeChatRooms) { //Loop over chat rooms
			if (room.getName().contentEquals(chatroomname)){ // Find correct chat room
				for(String UserId : room.getConnectedUsers()){ //Get all active users
						UserLeftChatRoomResponse userLeftChatRoomResponse = new UserLeftChatRoomResponse("UserLeftChatRoomResponse");
						userLeftChatRoomResponse.setDisplayname(info.getDisplayName());
						userLeftChatRoomResponse.setChatroomname(chatroomname);
						String sessionid = useridToSessionId(UserId);
						if (sessionid != null ) {
							userLeftChatRoomResponse.setRecipientSessionId(useridToSessionId(UserId));

							synchronized (messagesToSend) {
								messagesToSend.add(userLeftChatRoomResponse); //Send message.
							}
						}
					}
				return;
			}
		}
	}

	/**
	 * Does a chat room exist
	 * @param chatRoomName String containing chat room name
	 * @return true if chat room with given name returns, false if chat room does not exist
	 */
	private boolean chatRoomExists(String chatRoomName){
		for(ChatRoom room: activeChatRooms) {
			if (room.getName().toLowerCase().contentEquals(chatRoomName.toLowerCase())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Check if a chat room is a game rood.
	 * @param chatRoomName String containing chat room name
	 * @return True if it is a game room, false if not.
	 */
	private boolean chatRoomIsGameOnly(String chatRoomName){
		for(ChatRoom room: activeChatRooms) {
			if (room.getName().toLowerCase().contentEquals(chatRoomName.toLowerCase())) {
				return room.isGameRoom();
			}
		}
		return false;
	}

	/**
	 * Check if a user is allowed to join a chat room. (Only used for game rooms)
	 * @param chatRoomName String containing chat room name
	 * @param displayname String containing display name
	 * @return true if allowed, false if not.
	 */
	private boolean userIsAllowedInRoom(String chatRoomName, String displayname) {
		for(ChatRoom room: activeChatRooms) {
			if (room.getName().toLowerCase().contentEquals(chatRoomName.toLowerCase())) {
				for(String name: room.getAllowedUsers()){
					if (name.contentEquals(displayname)){
						return true;
					}
				}
			}
		}
		return false;
	}

    /**
     * Finds all names matching the search query.
     * @param action UserWantUserList message from user
     */
	private void UserWantsUsersList(UserWantsUsersList action){
		//Security check.
		boolean secCheckPass = securityCheck(action.getUserid(),action.getRecipientSessionId());
		if(!secCheckPass){
			System.out.println("Warning: " + action.getUserid() + " tried to request a user list, but " + action.getRecipientSessionId() + " does not match...");
			return;
		}

	    Message retMsg = new UsersListResponse("UsersListResponse");
	    retMsg.setRecipientSessionId(action.getRecipientSessionId());
	    UserInfo info_self = db.getProfile(sessionIdToUserId(retMsg.getRecipientSessionId()));

	    ArrayList<String> usersMatchQuery = new ArrayList<>();

        for (ChatRoom room : activeChatRooms) {
            for (String userid : room.getConnectedUsers()){
                UserInfo info = db.getProfile(userid);
                if (info.getDisplayName().contains(action.getSearchquery()) && !info.getDisplayName().contentEquals(info_self.getDisplayName())) {
                    if (!usersMatchQuery.contains(info.getDisplayName())){
                        usersMatchQuery.add(info.getDisplayName());
                    }
                }
            }
        }

        String[] retArr = new String[usersMatchQuery.size()];
        for (int i = 0; i < usersMatchQuery.size(); i++) {
            retArr[i] = usersMatchQuery.get(i);
        }

        ((UsersListResponse)retMsg).setDisplaynames(retArr);

        synchronized (messagesToSend) {
            messagesToSend.add(retMsg);
        }

    }

	/**
	 * When a user wants to create a game.
	 * @param action UserWantsToCreateGame message from user
	 */
	private void UserWantsToCreateGame(UserWantsToCreateGame action){
		//Security check.
		boolean secCheckPass = securityCheck(action.getHostid(),action.getRecipientSessionId());
		if(!secCheckPass){
			System.out.println("Warning: " + action.getHostid() + " tried to create a game, but " + action.getRecipientSessionId() + " does not match...");
			return;
		}

		CreateGameResponse retMsg = new CreateGameResponse("CreateGameResponse");
		retMsg.setRecipientSessionId(useridToSessionId(action.getHostid()));

		Ludo newGame = new Ludo();
		newGame.setHostid(action.getHostid());
		newGame.setGameid(UUID.randomUUID().toString());

		UserInfo info = db.getProfile(action.getHostid());
		newGame.addPlayer(info.getDisplayName());
		newGame.addDiceListener(this);
		newGame.addPieceListener(this);
		newGame.addPlayerListener(this);
		activeLudoGames.add(newGame);

		retMsg.setJoinstatus(true);
		retMsg.setResponse("server.gameJoinOk");
		retMsg.setGameid(newGame.getGameid());

		System.out.println("Active ludo games " + activeLudoGames.size());

		synchronized (messagesToSend) {
			messagesToSend.add(retMsg);
		}

		Invitations invites = new Invitations();
		invites.setPlayers(action.getToinvitedisplaynames());
		invites.setAccepted(new Boolean[action.getToinvitedisplaynames().length]);
		invites.setGameid(newGame.getGameid());
		pendingInvites.add(invites);

		//Send out invitations here:
		for (int i = 0; i < action.getToinvitedisplaynames().length; i++) {
			SendGameInvitationsResponse invite = new SendGameInvitationsResponse("SendGameInvitationsResponse");
			UserInfo userInfo = db.getProfilebyDisplayName(action.getToinvitedisplaynames()[i]);
			if (userInfo != null){
				invite.setGameid(newGame.getGameid());
				invite.setHostdisplayname(info.getDisplayName());
				invite.setRecipientSessionId(useridToSessionId(userInfo.getUserId()));
				synchronized (messagesToSend){
					messagesToSend.add(invite);
				}
			}
		}

		//Create a game room.
		ChatRoom newRoom = new ChatRoom(newGame.getGameid());
		newRoom.setGameRoom(true);
		ArrayList<String> names = new ArrayList<>();
		names.addAll(Arrays.asList(action.getToinvitedisplaynames()));
		names.add(info.getDisplayName());
		newRoom.setAllowedUsers(names);
		activeChatRooms.add(newRoom);
		try {
			db.insertChatRoom(newGame.getGameid());
		} catch (SQLException e) {
			e.printStackTrace();
		}

	}

	/**
	 * When a user answers a game invite.
	 * @param action UserDoesGameInvitationAnswer message from user
	 */
	private void UserDoesGameInvitationAnswer(UserDoesGameInvitationAnswer action) {
		//Security check.
		boolean secCheckPass = securityCheck(action.getUserid(),action.getRecipientSessionId());
		if(!secCheckPass){
			System.out.println("Warning: " + action.getUserid() + " tried to accept a invite, but " + action.getRecipientSessionId() + " does not match...");
			return;
		}

		Message retMsg;
		System.out.println(action);
		if (action.isAccepted()) { //User accepted. Add them to the game
			retMsg = new UserJoinedGameResponse("UserJoinedGameResponse");
			((UserJoinedGameResponse)retMsg).setGameid(action.getGameid());
			((UserJoinedGameResponse)retMsg).setUserid(action.getUserid());
			for(Ludo game : activeLudoGames) {
				System.out.println(game.getGameid() + " " + action.getGameid());
				if (game.getGameid().contentEquals(action.getGameid())) {
					UserInfo info = db.getProfile(action.getUserid());
					game.addPlayer(info.getDisplayName());
					((UserJoinedGameResponse)retMsg).setPlayersinlobby(game.getPlayers());
				}
			}

			for(Ludo game : activeLudoGames) {
				if (game.getGameid().contentEquals(action.getGameid())) {
					for (String name : game.getPlayers()) {
						if (name != null){
							UserInfo info = db.getProfilebyDisplayName(name);
							retMsg.setRecipientSessionId(useridToSessionId(info.getUserId()));
							synchronized (messagesToSend){
								messagesToSend.add(retMsg);
							}
						}
					}
				}
			}

			for (Invitations invite : pendingInvites) {
				if (invite.getGameid().contentEquals(action.getGameid())){
					UserInfo info = db.getProfile(action.getUserid());
					invite.setOneUpdate(info.getDisplayName(),true);
				}
			}

		} else { //User declined. Send message to inviter. Which is host of game (?)
			String hostId = null;
			for(Ludo game : activeLudoGames) {
				if (game.getGameid().contentEquals(action.getGameid())) {
					hostId = game.getHostid();
				}
			}
			retMsg = new UserDeclinedGameInvitationResponse("UserDeclinedGameInvitationResponse");
			retMsg.setRecipientSessionId(useridToSessionId(hostId));
			((UserDeclinedGameInvitationResponse)retMsg).setGameid(action.getGameid());
			((UserDeclinedGameInvitationResponse)retMsg).setUserid(action.getUserid());

			synchronized (messagesToSend){
				messagesToSend.add(retMsg);
			}

			for (Invitations invite : pendingInvites) {
				if (invite.getGameid().contentEquals(action.getGameid())){
					UserInfo info = db.getProfile(action.getUserid());
					invite.setOneUpdate(info.getDisplayName(),false);
				}
			}

		}
		checkIfEveryoneAnsweredInvite(action);
	}

	/**
	 * Checks if all invited users have answered the invite.
	 * @param action UserDoesGameInvitationAnswer message from user
	 */
	private void checkIfEveryoneAnsweredInvite(UserDoesGameInvitationAnswer action){
		for (Invitations invite : pendingInvites) {
			if (invite.getGameid().contentEquals(action.getGameid())){
				UserInfo info = db.getProfile(action.getUserid());

				if (invite.isEveryoneAccepted()){
					for (int i = 0; i < invite.getPlayers().length; i++){
						if (invite.getOnePlayerAccepted(i)){
							GameHasStartedResponse gameStarted = new GameHasStartedResponse("GameHasStartedResponse");
							gameStarted.setGameid(invite.getGameid());
							UserInfo userInfo = db.getProfilebyDisplayName(invite.getOnePlayerName(i));
							gameStarted.setRecipientSessionId(useridToSessionId(userInfo.getUserId()));

							synchronized (messagesToSend){
								messagesToSend.add(gameStarted);
							}

						}
					}
					//pendingInvites.remove(invite);
					for (Ludo game : activeLudoGames) {
						GameHasStartedResponse gameStarted = new GameHasStartedResponse("GameHasStartedResponse");
						gameStarted.setGameid(invite.getGameid());
						gameStarted.setRecipientSessionId(useridToSessionId(game.getHostid()));
						messagesToSend.add(gameStarted);
					}
				}

			}
		}
	}

	/**
	 * When a user leaves a game.
	 * @param action UserLeftGame message from user
	 */
	private void UserLeftGame(UserLeftGame action){

		UserLeftGameResponse retMsg = new UserLeftGameResponse("UserLeftGameResponse");
		UserInfo info = db.getProfile(sessionIdToUserId(action.getRecipientSessionId()));
		retMsg.setDisplayname(info.getDisplayName());
		retMsg.setGameid(action.getGameid());

		for (Ludo game : activeLudoGames) {
			if (game.getGameid().contentEquals(action.getGameid())) {
				game.removePlayer(info.getDisplayName());
				for(String name : game.getActivePlayers()){
					if (!name.contentEquals(info.getDisplayName())){
						UserInfo userInfo = db.getProfilebyDisplayName(name);
						retMsg.setRecipientSessionId(useridToSessionId(userInfo.getUserId()));
						System.out.println(name);
						synchronized (messagesToSend){
							messagesToSend.add(retMsg);
						}
					}
				}
			}
		}
	}

	/**
	 * When a user wants to search for a random game. Creates new if no games are available. Joins game if there are available games.
	 * @param action UserDoesRandomGameSearch message from user
	 */
	private void UserDoesRandomGameSearch(UserDoesRandomGameSearch action){
		//Security check.
		boolean secCheckPass = securityCheck(action.getUserid(),action.getRecipientSessionId());
		if(!secCheckPass){
			System.out.println("Warning: " + action.getUserid() + " tried to search a random game, but " + action.getRecipientSessionId() + " does not match...");
			return;
		}

		UserInfo info = db.getProfile(action.getUserid());

		boolean foundGame = false;

		for (Ludo game : activeLudoGames) {
			System.out.println("Status: " + game.getStatus());
			if (game.getStatus().contentEquals("Initiated") && game.getActivePlayers().length < 4){
				foundGame = true;
				game.addPlayer(info.getDisplayName());

				UserJoinedGameResponse retMsg = new UserJoinedGameResponse("UserJoinedGameResponse");
				retMsg.setGameid(game.getGameid());
				retMsg.setUserid(action.getUserid());
				retMsg.setPlayersinlobby(game.getPlayers());

				for (String name : game.getPlayers()) {
					UserInfo userInfo = db.getProfilebyDisplayName(name);
					retMsg.setRecipientSessionId(useridToSessionId(userInfo.getUserId()));
					synchronized (messagesToSend){
						messagesToSend.add(retMsg);
					}
				}

				for(ChatRoom room : activeChatRooms) {
					if (room.getName().contentEquals(game.getGameid())){
						ArrayList<String> names = room.getAllowedUsers();
						names.add(info.getDisplayName());
						room.setAllowedUsers(names);
					}
				}

				if(game.getActivePlayers().length == 4) {
					for (String name : game.getPlayers()) {
						Message gameStarted = new GameHasStartedResponse("GameHasStartedResponse");
						((GameHasStartedResponse)gameStarted).setGameid(game.getGameid());
						UserInfo userInfo = db.getProfilebyDisplayName(name);
						gameStarted.setRecipientSessionId(useridToSessionId(userInfo.getUserId()));
						synchronized (messagesToSend){
							messagesToSend.add(gameStarted);
						}
					}
				}

			}
		}

		if (!foundGame) {
			CreateGameResponse retMsg = new CreateGameResponse("CreateGameResponse");
			retMsg.setRecipientSessionId(action.recipientSessionId);

			Ludo newGame = new Ludo();
			newGame.setHostid(sessionIdToUserId(action.getRecipientSessionId()));
			newGame.setGameid(UUID.randomUUID().toString());

			newGame.addPlayer(info.getDisplayName());
			newGame.addDiceListener(this);
			newGame.addPieceListener(this);
			newGame.addPlayerListener(this);
			activeLudoGames.add(newGame);

			retMsg.setJoinstatus(true);
			retMsg.setResponse("server.gameJoinOk");
			retMsg.setGameid(newGame.getGameid());
			synchronized (messagesToSend) {
				messagesToSend.add(retMsg);
			}

			//Create a game room.
			ChatRoom newRoom = new ChatRoom(newGame.getGameid());
			newRoom.setGameRoom(true);
			ArrayList<String> names = new ArrayList<>();
			names.add(info.getDisplayName());
			newRoom.setAllowedUsers(names);
			activeChatRooms.add(newRoom);
			try {
				db.insertChatRoom(newGame.getGameid());
			} catch (SQLException e) {
				e.printStackTrace();
			}

		}

	}

	/**
	 * When a user wants to view a users profile
	 * @param action UserWantToViewProfile message from user
	 */
	private void UserWantToViewProfile(UserWantToViewProfile action){
		UserInfo info = db.getProfilebyDisplayName(action.getDisplayname());
		UserWantToViewProfileResponse retMsg;
		retMsg = new UserWantToViewProfileResponse("UserWantToViewProfileResponse");
		retMsg.setRecipientSessionId(action.getRecipientSessionId());
		if (info != null){
			retMsg.setImageString(info.getAvatarImage());
			retMsg.setDisplayName(info.getDisplayName());
			retMsg.setGamesPlayed(info.getGamesPlayed());
			retMsg.setGamesWon(info.getGamesWon());
			retMsg.setUserId(info.getUserId());
			retMsg.setMessage("");

		} else {
			retMsg.setMessage("server.userViewProfileFail");
		}

		synchronized (messagesToSend) {
			messagesToSend.add(retMsg);
		}

	}

	/**
	 * When a user wants to edit their profile.
	 * @param action UserWantToEditProfile message from user
	 */
	private void UserWantToEditProfile(UserWantToEditProfile action) {
		UserInfo oldInfo = db.getProfile(sessionIdToUserId(action.getRecipientSessionId()));
		//Security check.
		boolean secCheckPass = securityCheck(oldInfo.getUserId(),action.getRecipientSessionId());
		if(!secCheckPass){
			System.out.println("Warning: " + oldInfo.getUserId() + " tried to edit a user, but " + action.getRecipientSessionId() + " does not match...");
			return;
		}

		UserWantToEditProfileResponse retMsg = new UserWantToEditProfileResponse("UserWantToEditProfileResponse");
		retMsg.setRecipientSessionId(action.getRecipientSessionId());
		UserInfo newInfo = new UserInfo(sessionIdToUserId(action.getRecipientSessionId()), action.getDisplayname(),action.getImageString(), oldInfo.getGamesPlayed(), oldInfo.getGamesWon());

        boolean profileUpdate = false, passwordUpdate = false;

		try {
			if(!db.displaynameExists(newInfo.getDisplayName())){ //Displayname doesnt exist
				db.updateProfile(newInfo);
				profileUpdate = true;
			} else if (oldInfo.getDisplayName().contentEquals(newInfo.getDisplayName())) { //User are not changing displayname
				db.updateProfile(newInfo);
				profileUpdate = true;
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}

		if (!action.getPassword().isEmpty()){
			try {
				db.updateAccountPassword(sessionIdToUserId(action.getRecipientSessionId()), action.getPassword());
				passwordUpdate = true;
			} catch (SQLException e) {
				e.printStackTrace();
			}
		}

		if (profileUpdate && passwordUpdate) { //Both updated.
			retMsg.setChanged(true);
			retMsg.setResponse("server.userEditProfileOK");
			retMsg.setDisplayname(newInfo.getDisplayName());
            System.out.println(newInfo.toString());

        } else if (profileUpdate && !passwordUpdate) { //Only profile was updated
			retMsg.setChanged(true);
			retMsg.setResponse("server.userEditProfileNoPW");
			retMsg.setDisplayname(action.getDisplayname());

		} else if (!profileUpdate && passwordUpdate) { //Only password was updated
			retMsg.setChanged(true);
			retMsg.setResponse("server.userEditProfileOnlyPW");
			retMsg.setDisplayname(oldInfo.getDisplayName());

		} else { //Neither was updated.
			retMsg.setChanged(false);
			retMsg.setResponse("server.userEditProfileFail");
			retMsg.setDisplayname(oldInfo.getDisplayName());
		}

		synchronized (messagesToSend){
			messagesToSend.add(retMsg);
		}
	}

	/**
	 * When a user wants to view the leaderboard.
	 * @param action UserWantsLeaderboard message from user
	 */
	private void UserWantsLeaderboard(UserWantsLeaderboard action) {
		LeaderboardResponse retMsg = new LeaderboardResponse("LeaderboardResponse");
		retMsg.setRecipientSessionId(action.getRecipientSessionId());
		TopTenList toptenlist = db.getTopTenList();
		retMsg.setToptenwins(toptenlist.getWonEntries());
		retMsg.setToptenplays(toptenlist.getPlayedEntries());

		synchronized (messagesToSend){
			messagesToSend.add(retMsg);
		}
	}

	/**
	 * Checks if the userid and sessionid is the same client.
	 * Discards message if false.
	 * @param sessionid String containing user id
	 * @param userid String containing session id
	 * @return true if everything is fine, false is something missmatch.
	 */
	private boolean securityCheck(String userid, String sessionid) {
		System.out.println("Security check: " + userid + " " + sessionid);
		String sessUserId = sessionIdToUserId(sessionid);
		return (sessUserId.contentEquals(userid));
	}

	/**
	 * Implemented interface DiceListener
	 * @param diceEvent returns data about dice rolled
	 */
	@Override
	public void diceThrown(DiceEvent diceEvent) {
		Ludo game = diceEvent.getLudoGame();

		for (String name : game.getPlayers()){
			Message retMsg = new DiceThrowResponse("DiceThrowResponse");

			((DiceThrowResponse)retMsg).setGameid(game.getGameid());
			((DiceThrowResponse)retMsg).setDicerolled(diceEvent.getDiceRolled());
			UserInfo userInfo = db.getProfilebyDisplayName(name);
			retMsg.setRecipientSessionId(useridToSessionId(userInfo.getUserId()));
			synchronized (messagesToSend){
				messagesToSend.add(retMsg);
			}
		}


	}

	/**
	 * Implemented interface PieceListener
	 * @param pieceEvent returns data about the piece moved
	 */
	@Override
	public void pieceMoved(PieceEvent pieceEvent) {
		Ludo game = pieceEvent.getLudoGame();
		for (String name : game.getPlayers()){

			PieceMovedResponse retMsg = new PieceMovedResponse("PieceMovedResponse");

			retMsg.setGameid(game.getGameid());
			retMsg.setMovedfrom(pieceEvent.getFrom());
			retMsg.setMovedto(pieceEvent.getTo());
			retMsg.setPiecemoved(pieceEvent.getPieceMoved());
			retMsg.setPlayerid(pieceEvent.getPlayerID());
			UserInfo userInfo = db.getProfilebyDisplayName(name);
			retMsg.setRecipientSessionId(useridToSessionId(userInfo.getUserId()));
			synchronized (messagesToSend){
				messagesToSend.add(retMsg);
			}
		}

	}

	/**
	 * Implemented interface PlayerListener
	 * @param event PlayerEvent containing info about what state the player is in.
	 */
	@Override
	public void playerStateChanged(PlayerEvent event) {
		Ludo game = event.getLudo();
		if(event.getPlayerEvent().contentEquals("Won")){
			int playerid = 0; //Represents which player we are looping through. This works since ludo game id
							  // Have the same order as player names.
			for (String name : game.getActivePlayers()){
				UserInfo info = db.getProfilebyDisplayName(name);
				info.setGamesPlayed(info.getGamesPlayed() + 1);

				//ONLY FOR WINNER.
				if(playerid == event.getPlayerID()) {
					info.setGamesWon(info.getGamesWon() + 1);
				}

				try {
					db.updateProfile(info);
				} catch (SQLException e) {
					e.printStackTrace();
				}

				playerid++;
			}
		}
	}


}