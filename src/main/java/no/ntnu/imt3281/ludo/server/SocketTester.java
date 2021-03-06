package no.ntnu.imt3281.ludo.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.*;
import java.io.*;
import java.util.UUID;

/**
 * This code is only used to test message handling from the client without having
 * a client fully implemented.
 */

public class SocketTester {

    private static final int DEFAULT_PORT = 4567;

    UUID uuid = UUID.randomUUID();

    private Socket connection = null;
    private BufferedWriter bw;
    private BufferedReader br;

    private String gameid;

    private String message = "{\"action\" : \"UserDoesDiceThrow\", \"playerId\": 1, \"ludoId\" : 2}";


    public static void main(String[] args) {
        new SocketTester();
    }

    public SocketTester(){
            //establish socket connection to server
            try {
                connection = new Socket("127.0.0.1", DEFAULT_PORT);
                bw = new BufferedWriter(new OutputStreamWriter(connection.getOutputStream()));
                br = new BufferedReader(new InputStreamReader(connection.getInputStream()));

                //sendRegister();
                sendLogin();
                //sendAutoLogin();

                joinChatRoom();

                //listChatRooms();

                //listUserList();

                //createGameRequest();
                //joinGameChatRoom();
                //acceptGameInvite();

                //sendChatMessage();

                //removeFromChatRoom();

                //sendUserView();
                //sendUserEdit();
                //sendUserView();
                //TopTen();

                //sendChatMessage();
                //sendChatMessage();
                while(true){
                    String gotMessage = br.readLine();
                    if (!gotMessage.contains("Ping")) {
                        System.out.println(gotMessage);
                    }
                }

                /*gotMessage = br.readLine();
                System.out.println("Recieved: " + gotMessage); //Mainly for debugging purposes*/

                //Thread.sleep(100);
                //connection.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        private void sendRegister(){
            String RegisterMessage = "{\"action\" : \"UserDoesRegister\",\"username\": \"test5\", \"recipientSessionId\":\"458b2331-14f4-419f-99b1-ad492e8906fc\" ,\"password\": \"test5\"}";

            try {
                bw.write(RegisterMessage);
                bw.newLine();
                bw.flush();
                System.out.println("Sent message : " + RegisterMessage);

                String gotMessage = br.readLine();
                System.out.println("Recieved: " + gotMessage); //Mainly for debugging purposes
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

    private void sendLogin(){
        String LoginMessage = "{\"action\" : \"UserDoesLoginManual\" ,\"username\": \"test\" ,\"recipientSessionId\":\"458b2331-14f4-419f-99b1-ad492e8906fb\" ,\"password\": \"test1234\"}";
        //String LoginMessage = "{\"recipientuuid\":null ,\"action\" : \"U1serDoesLoginManual\" ,\"username\": \"test\" ,\"recipientSessionId\":\"458b2331-14f4-419f-99b1-ad492e8906fb\" ,\"password\": \"test\"}";

        try {
            bw.write(LoginMessage);
            bw.newLine();
            bw.flush();
            System.out.println("Sent message : " + LoginMessage);

            String gotMessage = br.readLine();
            System.out.println("Recieved: " + gotMessage); //Mainly for debugging purposes
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void sendAutoLogin(){
         String LoginMessage = "{\"action\" : \"UserDoesLoginAuto\" , \"recipientSessionId\":\"458b2331-14f4-419f-99b1-ad492e8906fb\"}";

        try {
            bw.write(LoginMessage);
            bw.newLine();
            bw.flush();
            System.out.println("Sent message : " + LoginMessage);

            String gotMessage = br.readLine();
            System.out.println("Recieved: " + gotMessage); //Mainly for debugging purposes
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    private void joinChatRoom(){
        String JoinChatMessage = "{\"action\" : \"UserJoinChat\", \"userid\" : \"\", \"chatroomname\" : null}";
        try {
            bw.write(JoinChatMessage);
            bw.newLine();
            bw.flush();
            System.out.println("Sent message : " + JoinChatMessage);

            String gotMessage = br.readLine();
            System.out.println("Recieved: " + gotMessage); //Mainly for debugging purposes

            Thread.sleep(500);
        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void sendChatMessage(){
        String ChatMessage = "{ \"action\": \"UserSentMessage\", \"userid\": \"2ecc4deb-e320-4fac-9834-2ee0a84edeca\" ,\"chatroomname\": \"Global\" , \"chatmessage\" : \"heisann\" }";

        try {
            bw.write(ChatMessage);
            bw.newLine();
            bw.flush();
            System.out.println("Sent message : " + ChatMessage);

            String gotMessage = br.readLine();
            System.out.println("Recieved: " + gotMessage); //Mainly for debugging purposes

            Thread.sleep(500);
        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }


    }

    private void removeFromChatRoom(){
        String ChatMessage = "{\"action\":\"UserLeftChatRoom\",\"userid\":\"2ecc4deb-e320-4fac-9834-2ee0a84edeca\" ,\"chatroomname\":\"Global\"}";

        try {
            bw.write(ChatMessage);
            bw.newLine();
            bw.flush();
            System.out.println("Sent message : " + ChatMessage);

            String gotMessage = br.readLine();
            System.out.println("Recieved: " + gotMessage); //Mainly for debugging purposes
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void listChatRooms(){
        String ChatMessage = "{\"action\":\"UserListChatrooms\"}";

        try {
            bw.write(ChatMessage);
            bw.newLine();
            bw.flush();
            System.out.println("Sent message : " + ChatMessage);

            String gotMessage = br.readLine();
            System.out.println("Recieved: " + gotMessage); //Mainly for debugging purposes
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    private void listUserList(){
        String ChatMessage = "{\"action\":\"UserWantsUsersList\", \"userid\": \"2ecc4deb-e320-4fac-9834-2ee0a84edeca\", \"searchquery\":\"tes\"}";

        try {
            bw.write(ChatMessage);
            bw.newLine();
            bw.flush();
            System.out.println("Sent message : " + ChatMessage);

            String gotMessage = br.readLine();
            System.out.println("Recieved: " + gotMessage); //Mainly for debugging purposes
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    private void createGameRequest(){
        String ChatMessage = "{\"action\":\"UserWantsToCreateGame\", \"hostid\": \"ee86c05b-52fe-44a5-9604-1e0337b53e8c\", \"toinvitedisplaynames\": [\"test\"]}";

        try {
            bw.write(ChatMessage);
            bw.newLine();
            bw.flush();
            System.out.println("Sent message : " + ChatMessage);

            String gotMessage = br.readLine();
            System.out.println("Recieved: " + gotMessage); //Mainly for debugging purposes

            ObjectMapper mapper = new ObjectMapper();
            JsonNode gameid_json = mapper.readTree(gotMessage);
            gameid = gameid_json.get("gameid").asText();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void acceptGameInvite(){
        String ChatMessage = "{\"action\":\"UserDoesGameInvitationAnswer\", \"accepted\": true, \"userid\":\"2ecc4deb-e320-4fac-9834-2ee0a84edeca\", \"gameid\":\""+gameid+"\"}";

        try {
            bw.write(ChatMessage);
            bw.newLine();
            bw.flush();
            System.out.println("Sent message : " + ChatMessage);

            String gotMessage = br.readLine();
            System.out.println("Recieved: " + gotMessage); //Mainly for debugging purposes
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void sendUserEdit(){
        String ChatMessage = "{\"action\":\"UserWantToEditProfile\",\"displayname\":\"testhei\",\"imageString\":null,\"password\":\"\"}";

        try {
            bw.write(ChatMessage);
            bw.newLine();
            bw.flush();
            System.out.println("Sent message : " + ChatMessage);

            String gotMessage = br.readLine();
            System.out.println("Recieved: " + gotMessage); //Mainly for debugging purposes
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void sendUserView(){
        String ChatMessage = "{\"action\":\"UserWantToViewProfile\",\"displayname\":\"testhei1\"}";

        try {
            bw.write(ChatMessage);
            bw.newLine();
            bw.flush();
            System.out.println("Sent message : " + ChatMessage);

            String gotMessage = br.readLine();
            System.out.println("Recieved: " + gotMessage); //Mainly for debugging purposes
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void joinGameChatRoom(){
        String ChatMessage = "{\"action\" : \"UserJoinChat\", \"userid\" : \"ee86c05b-52fe-44a5-9604-1e0337b53e8c\", \"chatroomname\" : \"" + gameid + "\"}";

        try {
            bw.write(ChatMessage);
            bw.newLine();
            bw.flush();
            System.out.println("Sent message : " + ChatMessage);

            String gotMessage = br.readLine();
            System.out.println("Recieved: " + gotMessage); //Mainly for debugging purposes
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void TopTen(){
        String ChatMessage = "{\"action\":\"UserWantsLeaderboard\"}";

        try {
            bw.write(ChatMessage);
            bw.newLine();
            bw.flush();
            System.out.println("Sent message : " + ChatMessage);

            String gotMessage = br.readLine();
            System.out.println("Recieved: " + gotMessage); //Mainly for debugging purposes
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


}