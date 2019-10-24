package no.ntnu.imt3281.ludo.server;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.sql.*;

import static org.junit.Assert.*;

public class DatabaseTest {
    private static final String dbURL = "jdbc:derby:./ludoTestDB";
    private static Database testDatabase;       // our test database
    private static Connection testConnection;          // database connection to execute sql statements

    /**
     * Sets up database before any other test is run and
     * checks if we can connect to it
     */
    @BeforeClass
    public static void setupDatabase(){
        try{
            testDatabase = Database.constructTestDatabase(dbURL);
        } catch(Exception ex){
            assertFalse(true);
        }

        // we are supposed to get the connection
        try{
            testConnection = DriverManager.getConnection(dbURL);
        } catch(SQLException e){
            System.out.println(e.getMessage());
            // fail testing if not
            assertFalse(true);
        }

        // check if we have reached this point in test (since testDatabase does exit(1) on worst case)
        assertTrue(true);
    }

    /**
     * Remove all data at end of tests (excluding DROP table)
     */
    @AfterClass
    public static void purgeDatabase(){
        // remove all data from records
        try{
            Statement statement = testConnection.createStatement();
            statement.execute("DELETE FROM user_info");
            statement.execute("DELETE FROM chat_log");
        } catch(SQLException ex){
            assertFalse(true);
        }
    }


    /**
     * Tests that our database was correctly setup with the desired tables and columns
     */
    @Test
    public void initialTest(){
        Statement statement;
        ResultSet resultSet = null;

        // test user_info table
        try{
            statement = testConnection.createStatement();
            resultSet = statement.executeQuery("SELECT * FROM user_info");
        } catch(SQLException ex){
            assertFalse(true);
        }

        // check if tables have all the required columns and only has 5 columns
        try{
            assertEquals("USER_ID", resultSet.getMetaData().getColumnName(1));
            assertEquals("USER_NAME", resultSet.getMetaData().getColumnName(2));
            assertEquals("AVATAR_PATH", resultSet.getMetaData().getColumnName(3));
            assertEquals("GAMES_PLAYED", resultSet.getMetaData().getColumnName(4));
            assertEquals("GAMES_WON", resultSet.getMetaData().getColumnName(5));
            assertEquals(5, resultSet.getMetaData().getColumnCount());
        } catch(SQLException ex){
            assertFalse(true);
        }

        // test chat_log table
        try{
            statement = testConnection.createStatement();
            resultSet = statement.executeQuery("SELECT * FROM chat_log");
        } catch(SQLException ex){
            assertFalse(true);
        }

        // check if tables have all the required columns and only has 5 columns
        try{
            assertEquals("CHAT_ID", resultSet.getMetaData().getColumnName(1));
            assertEquals("USER_ID", resultSet.getMetaData().getColumnName(2));
            assertEquals("CHAT_NAME", resultSet.getMetaData().getColumnName(3));
            assertEquals("ROOM_ID", resultSet.getMetaData().getColumnName(4));
            assertEquals("CHAT_MESSAGE", resultSet.getMetaData().getColumnName(5));
            assertEquals("TIMESTAMP", resultSet.getMetaData().getColumnName(6));
            assertEquals(6, resultSet.getMetaData().getColumnCount());
        } catch(SQLException ex){
            assertFalse(true);
        }
    }

    @Test
    public void insertChatMessageTest(){

        //Insert data into the db
        testDatabase.insertChatMessage(0, 1,"Test","Hello Test");

        //Try to retrieve data from db and check if the data is correct.
        try {
            Statement state = testConnection.createStatement();
            ResultSet rs = state.executeQuery("SELECT * FROM chat_log");

            //Loop over data and
            while(rs.next()) {
                assertEquals(String.valueOf(0), rs.getString("chat_id"));
                assertEquals(String.valueOf(0), rs.getString("room_id"));
                assertEquals(String.valueOf(1), rs.getString("user_id"));
                assertEquals("Test", rs.getString("chat_name"));
                assertEquals("Hello Test", rs.getString("chat_message"));
                assertNotEquals(String.valueOf(0), rs.getString("timestamp"));
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }

    }

}