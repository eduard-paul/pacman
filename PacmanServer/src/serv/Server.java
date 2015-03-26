package serv;

import java.awt.Point;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Vector;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import myLib.*;

public class Server {

	private ServerSocket ss;
	private Thread serverThread;
	private int port;
	BlockingQueue<User> allUsers = new LinkedBlockingQueue<User>();
	BlockingQueue<Room> rooms = new LinkedBlockingQueue<Room>();

	public static void main(String[] args) throws IOException {
		new Server(6666).run();
	}

	public Server(int port) throws IOException {

		ss = new ServerSocket(port);
		this.port = port;

		initialize();

	}

	private void initialize() {

	}

	void run() {
		serverThread = Thread.currentThread();
		while (true) {
			Socket s = getNewConn();
			if (serverThread.isInterrupted()) {
				break;
			} else if (s != null) {
				try {
					final User processor = new User(s);
					final Thread thread = new Thread(processor);
					thread.setDaemon(true);
					thread.start();
					allUsers.offer(processor);
					System.out.println(s.toString() + " connected");
				} catch (IOException ignored) {
				}
			}
		}
	}

	private Socket getNewConn() {
		Socket s = null;
		try {
			s = ss.accept();
		} catch (IOException e) {
			shutdownServer();
		}
		return s;
	}

	private synchronized void shutdownServer() {
		for (User s : allUsers) {
			s.close();
		}
		if (!ss.isClosed()) {
			try {
				ss.close();
			} catch (IOException ignored) {
			}
		}
	}

	private class User implements Runnable {
		/** Request socket */
		Socket s;
		/** Data socket */
		Socket ds;
		private InputStream sin;
		private OutputStream sout;
		ObjectOutputStream dObjOut;
		ObjectInputStream oin;
		DataOutputStream dOut;
		DataInputStream din;
		String myRoomName = "";
		Room myRoom = null;
		int playerId = -1;

		User(Socket socketParam) throws IOException {
			s = socketParam;
			ds = new Socket(s.getInetAddress(), s.getPort() + 1);
			this.sin = s.getInputStream();
			this.sout = s.getOutputStream();
			dObjOut = new ObjectOutputStream(ds.getOutputStream());
			dOut = new DataOutputStream(ds.getOutputStream());
			din = new DataInputStream(ds.getInputStream());
			oin = new ObjectInputStream(s.getInputStream());
			Thread secondReader = new Thread(new Runnable() {
				@Override
				public void run() {

					while (!ds.isClosed()) {
						String line = null;

						try {
							line = din.readUTF();
							System.out
									.println("The dumb client just sent me this line : "
											+ line);
						} catch (IOException e) {
							close();
						}

						if (line == null) {
							close();
						} else {
							myRoom.Command(line, playerId);
						}
					}
				}
			});
			secondReader.start();
		}

		public void run() {

			while (!s.isClosed()) {

				String line = null;

				DataInputStream in = new DataInputStream(sin);

				try {
					line = in.readUTF();
					System.out
							.println("The dumb client just sent me this line : "
									+ line);
				} catch (IOException e) {
					close();
				}

				if (line == null) {
					close();
				} else if ("shutdown".equals(line)) {
					serverThread.interrupt();
					try {
						new Socket("localhost", port); // create fake connection
														// (to leave
														// ".accept()")
					} catch (IOException ignored) {
					} finally {
						shutdownServer();
					}
				} else if ("RefreshRoomList".equals(line)) {
					SendRoomList();
				} else if (line.contains("CreateRoom:")) {
					CreateRoom(line);
				} else if (line.contains("CustomRoom:")) {
					CustomRoom(line);
				} else if (line.contains("EnterRoom:")) {
					EnterRoom(line);
				} else if (line.contains("LeaveRoom")) {
					LeaveRoom();
				} else if (line.contains("SpectateRoom:")) {
					SpectateRoom(line);
				}
			}
		}

		public void SendBoard(int[][] board) {
			try {
				dObjOut.writeObject(board);
				dObjOut.flush();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

		public synchronized void LeaveRoom() {
			if ("spectate".equals(myRoomName))
				myRoom.RemoveSpectator(this);
			else
				myRoom.RemovePlayer(this);
			myRoomName = "";
			myRoom = null;
			playerId = -1;
		}

		public synchronized void CustomRoom(String line) {
			DataOutputStream out = new DataOutputStream(sout);
			boolean failed = false;
			String name = line.substring(11);
			for (Room room : rooms) { // Check if the same already exists
				if (room.name.equals(name))
					failed = true;
			}
			if (!failed) {
				try {					
					CustomBoard cb = (CustomBoard) oin.readObject();
					myRoom = new Room(name, cb, this);
					rooms.offer(myRoom);
					myRoomName = name;
					out.writeUTF("success");
					out.flush();
				} catch (Exception e) {
					try {
						out.writeUTF("fail");
						out.flush();
					} catch (IOException e1) {
					}
				}
			} else {
				try {
					out.writeUTF("fail");
					out.flush();
				} catch (IOException e) {
				}
			}
		}

		public synchronized void CreateRoom(String line) {
			DataOutputStream out = new DataOutputStream(sout);
			String strMaxPlayers = line.substring(11, 12);
			int maxPlayers = Integer.parseInt(strMaxPlayers);
			String name = line.substring(13);
			boolean failed = false;
			for (Room room : rooms) { // Check if the same already exists
				if (room.name.equals(name))
					failed = true;
			}
			if (!failed) {
				myRoom = new Room(name, maxPlayers, this);
				rooms.offer(myRoom);
				myRoomName = name;

				try {
					out.writeUTF("success");
					out.flush();
				} catch (IOException e) {
				}
			} else {
				try {
					out.writeUTF("fail");
					out.flush();
				} catch (IOException e) {
				}
			}
		}

		public synchronized void EnterRoom(String line) {
			DataOutputStream out = new DataOutputStream(sout);
			String name = line.substring(10);
			boolean failed = true;
			for (Room room : rooms) {
				if (room.name.equals(name)) {
					if (room.currPlayers < room.maxPlayers && !room.IsStarted()) {
						room.AddPlayer(this);
						myRoomName = name;
						myRoom = room;
						failed = false;
					}
				}
			}
			if (!failed) {
				try {
					out.writeUTF("success");
					out.flush();
				} catch (IOException e) {
				}
			} else {
				try {
					out.writeUTF("fail");
					out.flush();
				} catch (IOException e) {
				}
			}
		}

		public synchronized void SpectateRoom(String line) {
			DataOutputStream out = new DataOutputStream(sout);
			String name = line.substring(13);
			boolean failed = true;
			for (Room room : rooms) {
				if (room.name.equals(name)) {
					room.AddSpectator(this);
					myRoomName = "spectate";
					myRoom = room;
					failed = false;
				}
			}
			if (!failed) {
				try {
					out.writeUTF("success");
					out.flush();
				} catch (IOException e) {
				}
			} else {
				try {
					out.writeUTF("fail");
					out.flush();
				} catch (IOException e) {
				}
			}
		}

		public void SendRoomList() {
			DataOutputStream out = new DataOutputStream(sout);
			try {
				String list = new String();
				for (Room room : rooms) {
					list += ":" + room.name + " " + "[" + room.currPlayers
							+ "/" + room.maxPlayers + "]";
				}
				if (list.isEmpty())
					out.writeUTF("empty");
				else
					out.writeUTF(list.substring(1));
				out.flush();
			} catch (IOException e) {
				close();
			}
		}

		public synchronized void close() {
			if (!myRoomName.isEmpty()) { // If in some room leave it
				if ("spectate".equals(myRoomName))
					myRoom.RemoveSpectator(this);
				else
					myRoom.RemovePlayer(this);
			}
			allUsers.remove(this); // Remove itself from global user list
			if (!s.isClosed()) { // Try to close request socket
				try {
					s.close();
					System.out.println(s.toString() + " disconnected");
				} catch (IOException ignored) {
				}
			}
			if (!ds.isClosed()) { // Try to close data socket
				try {
					ds.close();
				} catch (IOException ignored) {
				}
			}
		}

		@Override
		protected void finalize() throws Throwable {
			super.finalize();
			close();
		}

		public void SendState(GameState gs) {
			try {
				dObjOut.writeObject(gs);
				dObjOut.flush();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

		public void SendStart() {
			try {
				dOut.writeUTF("StartGame");
				dOut.flush();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

	}

	private class Room {
		private String name;
		private int maxPlayers;
		private int currPlayers;
		private Game game;
		private boolean isStarted = false;
		private BlockingQueue<User> players = new LinkedBlockingQueue<User>();
		private BlockingQueue<User> spectators = new LinkedBlockingQueue<User>();
		private Timer timer = new java.util.Timer();

		private TimerTask task = new TimerTask() {
			public void run() {
				SendState();

			}
		};

		public boolean IsStarted() {
			return isStarted;
		}

		public void Command(String line, int id) {
			if ("Up".equals(line)) {
				game.GoUp(id);
			} else if ("Down".equals(line)) {
				game.GoDown(id);
			} else if ("Left".equals(line)) {
				game.GoLeft(id);
			} else if ("Right".equals(line)) {
				game.GoRight(id);
			}
		}

		public Room(String name, int maxPlayers, User firstPlayer) {

			this.name = name;
			this.maxPlayers = maxPlayers;
			this.currPlayers = 1;
			players.offer(firstPlayer);

			game = new Game(maxPlayers);

			if (currPlayers == maxPlayers)
				StartGame();
		}

		public Room(String name, CustomBoard cb, User firstPlayer) {

			this.name = name;
			this.maxPlayers = cb.playersStartPoints.size();
			this.currPlayers = 1;
			players.offer(firstPlayer);

			game = new Game(cb);

			if (currPlayers == maxPlayers)
				StartGame();
		}

		public void AddPlayer(User firstPlayer) {
			if (currPlayers < maxPlayers && !isStarted) {
				players.offer(firstPlayer);
				currPlayers = players.size();
				if (currPlayers == maxPlayers) {
					StartGame();
				}
			}
		}

		public void AddSpectator(User spectator) {
			if (IsStarted()) {
				spectator.SendStart();
				spectator.SendBoard(game.board.board);
			}
			spectators.offer(spectator);
		}

		private void StartGame() {
			int i = 1;
			isStarted = true;
			for (User player : players) {
				player.playerId = i++;
				player.SendStart();
			}
			for (User spectator : spectators) {
				spectator.SendStart();
			}
			SendBoard();
			timer.schedule(task, 0, 50);

			game.start();
			try {
				game.join();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}

		private void SendState() {
			GameState gs = game.getGameState();
			for (User player : players) {
				player.SendState(gs);
			}
			for (User spectator : spectators) {
				spectator.SendState(gs);
			}
		}

		private void SendBoard() {
			for (User player : players) {
				player.SendBoard(game.board.board);
			}
			for (User spectator : spectators) {
				spectator.SendBoard(game.board.board);
			}
		}

		public void RemovePlayer(User player) {
			players.remove(player);
			if (!IsStarted())
				currPlayers = players.size();
			if (players.size() == 0) {
				rooms.remove(this);
			}
		}

		public void RemoveSpectator(User spectator) {
			spectators.remove(spectator);
		}

		@Override
		protected void finalize() throws Throwable {
			super.finalize();
			timer.cancel();
		}
	}

	private class Game extends Thread {
		private final int[][] defaultBoard = {
				{ -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
						-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
				{ -1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, -1, 0, 0, 0, 0,
						0, 0, 0, 0, 0, 0, 0, 0, -1 },
				{ -1, 0, -1, -1, -1, -1, 0, -1, -1, -1, -1, -1, 0, -1, -1, 0,
						-1, -1, -1, -1, -1, 0, -1, -1, -1, -1, 0, -1 },
				{ -1, 0, -1, -1, -1, -1, 0, -1, -1, -1, -1, -1, 0, -1, -1, 0,
						-1, -1, -1, -1, -1, 0, -1, -1, -1, -1, 0, -1 },
				{ -1, 0, -1, -1, -1, -1, 0, -1, -1, -1, -1, -1, 0, -1, -1, 0,
						-1, -1, -1, -1, -1, 0, -1, -1, -1, -1, 0, -1 },
				{ -1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
						0, 0, 0, 0, 0, 0, 0, -1 },
				{ -1, 0, -1, -1, -1, -1, 0, -1, -1, 0, -1, -1, -1, -1, -1, -1,
						-1, -1, 0, -1, -1, 0, -1, -1, -1, -1, 0, -1 },
				{ -1, 0, -1, -1, -1, -1, 0, -1, -1, 0, -1, -1, -1, -1, -1, -1,
						-1, -1, 0, -1, -1, 0, -1, -1, -1, -1, 0, -1 },
				{ -1, 0, 0, 0, 0, 0, 0, -1, -1, 0, 0, 0, 0, -1, -1, 0, 0, 0, 0,
						-1, -1, 0, 0, 0, 0, 0, 0, -1 },
				{ -1, -1, -1, -1, -1, -1, 0, -1, -1, -1, -1, -1, 0, -1, -1, 0,
						-1, -1, -1, -1, -1, 0, -1, -1, -1, -1, -1, -1 },
				{ -1, -1, -1, -1, -1, -1, 0, -1, -1, -1, -1, -1, 0, -1, -1, 0,
						-1, -1, -1, -1, -1, 0, -1, -1, -1, -1, -1, -1 },
				{ -1, -1, -1, -1, -1, -1, 0, -1, -1, 0, 0, 0, 0, 0, 0, 0, 0, 0,
						0, -1, -1, 0, -1, -1, -1, -1, -1, -1 },
				{ -1, -1, -1, -1, -1, -1, 0, -1, -1, 0, -1, -1, -1, 0, 0, -1,
						-1, -1, 0, -1, -1, 0, -1, -1, -1, -1, -1, -1 },
				{ -1, -1, -1, -1, -1, -1, 0, -1, -1, 0, -1, 0, 0, 0, 0, 0, 0,
						-1, 0, -1, -1, 0, -1, -1, -1, -1, -1, -1 },
				{ 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, 0, -1, -1, -1, -1, 0, -1,
						0, 0, 0, 0, 0, 0, 0, 0, 0, 0 },
				{ -1, -1, -1, -1, -1, -1, 0, -1, -1, 0, -1, 0, 0, 0, 0, 0, 0,
						-1, 0, -1, -1, 0, -1, -1, -1, -1, -1, -1 },
				{ -1, -1, -1, -1, -1, -1, 0, -1, -1, 0, -1, -1, -1, 0, 0, -1,
						-1, -1, 0, -1, -1, 0, -1, -1, -1, -1, -1, -1 },
				{ -1, -1, -1, -1, -1, -1, 0, -1, -1, 0, 0, 0, 0, 0, 0, 0, 0, 0,
						0, -1, -1, 0, -1, -1, -1, -1, -1, -1 },
				{ -1, -1, -1, -1, -1, -1, 0, -1, -1, 0, -1, -1, -1, -1, -1, -1,
						-1, -1, 0, -1, -1, 0, -1, -1, -1, -1, -1, -1 },
				{ -1, -1, -1, -1, -1, -1, 0, -1, -1, 0, -1, -1, -1, -1, -1, -1,
						-1, -1, 0, -1, -1, 0, -1, -1, -1, -1, -1, -1 },
				{ -1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, -1, 0, 0, 0, 0,
						0, 0, 0, 0, 0, 0, 0, 0, -1 },
				{ -1, 0, -1, -1, -1, -1, 0, -1, -1, -1, -1, -1, 0, -1, -1, 0,
						-1, -1, -1, -1, -1, 0, -1, -1, -1, -1, 0, -1 },
				{ -1, 0, -1, -1, -1, -1, 0, -1, -1, -1, -1, -1, 0, -1, -1, 0,
						-1, -1, -1, -1, -1, 0, -1, -1, -1, -1, 0, -1 },
				{ -1, 0, 0, 0, -1, -1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
						0, 0, 0, -1, -1, 0, 0, 0, -1 },
				{ -1, -1, -1, 0, -1, -1, 0, -1, -1, 0, -1, -1, -1, -1, -1, -1,
						-1, -1, 0, -1, -1, 0, -1, -1, 0, -1, -1, -1 },
				{ -1, -1, -1, 0, -1, -1, 0, -1, -1, 0, -1, -1, -1, -1, -1, -1,
						-1, -1, 0, -1, -1, 0, -1, -1, 0, -1, -1, -1 },
				{ -1, 0, 0, 0, 0, 0, 0, -1, -1, 0, 0, 0, 0, -1, -1, 0, 0, 0, 0,
						-1, -1, 0, 0, 0, 0, 0, 0, -1 },
				{ -1, 0, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 0, -1, -1, 0,
						-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 0, -1 },
				{ -1, 0, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 0, -1, -1, 0,
						-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 0, -1 },
				{ -1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
						0, 0, 0, 0, 0, 0, 0, -1 },
				{ -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
						-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 } };
		private final Point[] defaultPlayersStartPoints = { new Point(1, 1),
				new Point(1, 26), new Point(29, 26), new Point(29, 1) };
		private final Point[] defaultGhostsStartPoints = { new Point(13, 11),
				new Point(15, 11), new Point(13, 16), new Point(15, 16) };
		private final Point[] playersStartPoints;
		private final Point[] ghostsStartPoints; 
		private final int DefaultSpeed = 300;
		private final int TimerPeriod = 30;

		int playersNum, ghostsNum = 4;
		int catchedPlayers = 0;
		int totalFood = 0, catchedFood = 0;
		Board board;
		Vector<Character> characters = new Vector<Character>();
		Timer timer = new java.util.Timer();
		protected boolean restarting = false;

		TimerTask task = new TimerTask() {
			public void run() {
				for (Character character : characters) {
					if (character.speed != 0)
						character.move();
				}
				if (restarting) {
					try {
						Thread.sleep(3000);
					} catch (InterruptedException e) {
					}
					Restart();
				}
			}
		};

		public void GoUp(int id) {
			for (Character ch : characters) {
				if (ch.id == id) {
					ch.setDesiredDirection(-2);
				}
			}
		}

		public void GoDown(int id) {
			for (Character ch : characters) {
				if (ch.id == id) {
					ch.setDesiredDirection(2);
				}
			}
		}

		public void GoLeft(int id) {
			for (Character ch : characters) {
				if (ch.id == id) {
					ch.setDesiredDirection(-1);
				}
			}
		}

		public void GoRight(int id) {
			for (Character ch : characters) {
				if (ch.id == id) {
					ch.setDesiredDirection(1);
				}
			}
		}

		public Game(int playersNum) {
			
			this.playersStartPoints = defaultPlayersStartPoints;
			this.ghostsStartPoints = defaultGhostsStartPoints;
			this.playersNum = playersNum;
			board = new Board();
			for (int[] row : board.board) {
				for (int i : row) {
					if (i == 0) {
						totalFood++;
					}
				}
			}
			for (int i = 0; i < board.board.length; i++) {
				for (int j = 0; j < board.board[0].length; j++) {
					if (board.board[i][j] == 0) {
						totalFood++;
						board.board[i][j] = 5;
					}
				}
			}
		}

		public Game(CustomBoard cb) {
			// TODO Auto-generated constructor stub
			this.playersNum = cb.playersStartPoints.size();
			this.playersStartPoints = cb.playersStartPoints.toArray(new Point[0]);
			this.ghostsStartPoints = cb.ghostsStartPoints.toArray(new Point[0]);
			board = new Board(cb);
			for (int[] row : board.board) {
				for (int i : row) {
					if (i != -1) {
						totalFood++;
					}
				}
			}
			for (int i = 0; i < board.board.length; i++) {
				for (int j = 0; j < board.board[0].length; j++) {
					if (board.board[i][j] != -1) {
						totalFood++;
						board.board[i][j] = 5;
					}
				}
			}
		}

		@Override
		public void run() {
			for (int i = 0; i < playersNum; i++) {
				int direction = 1;
				if (i % 2 == 1)
					direction = -1;
				characters.add(new Player(playersStartPoints[i],
						direction, DefaultSpeed, i + 1));
				board.setCellState(playersStartPoints[i], 1);
			}

			for (int i = 0; i < ghostsNum; i++) {
				int direction = 1;
				if (i % 2 == 1)
					direction = -1;
				characters.add(new Ghost(ghostsStartPoints[i],
						direction, DefaultSpeed, -1 - i, characters.get(i
								% playersNum)));
				board.setCellState(ghostsStartPoints[i], -2);
			}
			timer.schedule(task, 0, TimerPeriod);
		}

		private void Restart() {
			board.Clean();
			for (int i = 0; i < playersNum; i++) {
				int direction = 1;
				if (i % 2 == 1)
					direction = -1;
				Character player = characters.get(i);
				player.Setter(playersStartPoints[i], direction,
						DefaultSpeed);
			}
			for (int i = playersNum; i < playersNum + ghostsNum; i++) {
				int direction = 1;
				if (i % 2 == 1)
					direction = -1;
				Character ghost = characters.get(i);
				ghost.Setter(ghostsStartPoints[i - playersNum],
						direction, DefaultSpeed);
			}
			catchedPlayers = 0;
			catchedFood = 0;
			restarting = false;
		}

		private class Board {
			protected int board[][];
			protected int cleanBoard[][];

			public Board(CustomBoard cb) {
				board = new int[cb.board.length][cb.board[0].length];
				cleanBoard = new int[cb.board.length][cb.board[0].length];
				for (int i = 0; i < board.length; i++) {
					for (int j = 0; j < board[0].length; j++) {
						board[i][j] = cb.board[i][j];
						cleanBoard[i][j] = cb.board[i][j];
					}
				}
			}
			/**
			 * Creates the default board
			 */
			public Board() {
				board = new int[defaultBoard.length][defaultBoard[0].length];
				cleanBoard = new int[defaultBoard.length][defaultBoard[0].length];
				for (int i = 0; i < board.length; i++) {
					for (int j = 0; j < board[0].length; j++) {
						board[i][j] = defaultBoard[i][j];
						cleanBoard[i][j] = defaultBoard[i][j];
					}
				}
			}
			
			public void Clean() {
				for (int i = 0; i < board.length; i++) {
					for (int j = 0; j < board[0].length; j++) {
						board[i][j] = cleanBoard[i][j];
						if (board[i][j] == 0)
							board[i][j] = 5;
					}
				}
			}

			/**
			 * @param state
			 *            '0' - empty, '1' - hero, '-1' - wall, '-2' - ghost
			 */
			public void setCellState(Point cell, int state) {
				board[cell.x][cell.y] = state;
			}

			/**
			 * @return '0' - empty, '1' - hero, '-1' - wall, '-2' - ghost
			 */
			public int getCellState(Point cell) {
				return board[cell.x][cell.y];
			}
		}

		protected GameState getGameState() {
			Vector<CharacterState> cs = new Vector<CharacterState>();
			for (Character character : characters) {
				cs.add(character.getCharState());
			}
			GameState gs = new GameState(cs, board.board);
			return gs;
		}

		abstract class Character {
			protected int id;
			protected Point cell;
			protected double dist = 0;
			protected int direction;
			protected int speed;
			protected int desiredDirection;
			protected int myFood = -1;

			public CharacterState getCharState() {
				CharacterState s = new CharacterState();
				s.id = id;
				s.cell = (Point) cell.clone();
				s.dist = dist;
				s.direction = direction;
				s.speed = speed;
				return s;
			}

			/**
			 * @param direction
			 *            "1" - right, "-1" - left, "2" - down, "-2" - up
			 * @param speed
			 *            Time in Ms needed to reach next cell
			 */
			public Character(Point cell, int direction, int speed, int id) {
				this.cell = (Point) cell.clone();
				this.direction = direction;
				this.desiredDirection = direction;
				this.speed = speed;
				this.id = id;
			}

			public void Setter(Point cell, int direction, int speed) {
				this.cell = (Point) cell.clone();
				this.direction = direction;
				this.desiredDirection = direction;
				this.speed = speed;
				dist = 0;
			}

			public void setDesiredDirection(int dd) {
				desiredDirection = dd;
			}

			public abstract void move();

			protected Point DesiredCell() {
				Point result = (Point) cell.clone();

				switch (desiredDirection) {
				case 1:
					result.y++;
					if (result.y == 28)
						result.y = 0;
					break;
				case -1:
					result.y--;
					if (result.y == -1)
						result.y = 27;
					break;
				case 2:
					result.x++;
					break;
				case -2:
					result.x--;
					break;

				default:
					break;
				}

				return result;
			}

			protected Point NextCell() {
				Point result = (Point) cell.clone();

				switch (direction) {
				case 1:
					result.y++;
					if (result.y == 28)
						result.y = 0;
					break;
				case -1:
					result.y--;
					if (result.y == -1)
						result.y = 27;
					break;
				case 2:
					result.x++;
					break;
				case -2:
					result.x--;
					break;

				default:
					break;
				}

				return result;
			}
		}

		private class Player extends Character {
			/**
			 * @param direction
			 *            "1" - right, "-1" - left, "2" - down, "-2" - up
			 * @param speed
			 *            Time in ms needed to reach next cell
			 */
			public Player(Point cell, int direction, int speed, int id) {
				super(cell, direction, speed, id);
				myFood = 0;
			}

			@Override
			public void Setter(Point cell, int direction, int speed) {
				super.Setter(cell, direction, speed);
				this.myFood = 0;
			}

			@Override
			public void move() {
				if (Math.abs(desiredDirection) == Math.abs(direction))
					direction = desiredDirection;

				if (board.getCellState(NextCell()) != -1
						|| (Math.abs(dist) > 1.1 * TimerPeriod / speed)) {
					dist += 2 * Math.signum(direction) * TimerPeriod / speed;
					if (Math.abs(dist) >= 1) {
						if (board.getCellState(NextCell()) < -1) {
							this.speed = 0;
							this.dist = 0;
							this.cell = new Point(0, playersNum
									- catchedPlayers);
							catchedPlayers++;
							if (catchedPlayers == playersNum) {
								restarting = true;
							}
						} else {
							board.setCellState(cell, 0);
							cell = NextCell();
							dist -= 2 * Math.signum(dist);
							if (board.getCellState(cell) == 5) {
								myFood++;
								catchedFood++;
								if (catchedFood == totalFood) {
									ShowResults();
								}
							}
						}
					}
				}

				if (this.speed != 0) {
					board.setCellState(cell, this.id);

					if (board.getCellState(DesiredCell()) != -1
							&& (Math.abs(dist) < 1.1 * TimerPeriod / speed)) {
						direction = desiredDirection;
					}
				}
			}

		}

		private class Ghost extends Character {
			private Character aim;
			private boolean foodFlag = true; // If the ghost picked up food

			/**
			 * @param direction
			 *            "1" - right, "-1" - left, "2" - down, "-2" - up
			 * @param speed
			 *            Time in ms needed to reach next cell
			 */
			public Ghost(Point cell, int direction, int speed, int id,
					Character character) {
				super(cell, direction, speed, id);
				this.aim = character;
			}

			@Override
			public void Setter(Point cell, int direction, int speed) {
				super.Setter(cell, direction, speed);
				this.foodFlag = true;
			}

			@Override
			public void move() {

				Point aimCell = null;

				Point right = RightCell();
				Point left = LeftCell();
				Point next = NextCell();

				double minDistToAim = Double.MAX_VALUE;

				Random rand = new Random();

				int offsetX = (rand.nextInt(3) - 1) * 4;
				int offsetY = (rand.nextInt(3) - 1) * 4;

				aimCell = new Point(aim.cell.x + offsetX, aim.cell.y + offsetY);

				if (board.getCellState(next) != -1
						&& next.distance(aimCell) < minDistToAim) {
					desiredDirection = direction;
					minDistToAim = next.distance(aimCell);
				}
				if (board.getCellState(right) != -1
						&& right.distance(aimCell) < minDistToAim) {
					TurnRight();
					minDistToAim = right.distance(aimCell);
				}
				if (board.getCellState(left) != -1
						&& left.distance(aimCell) < minDistToAim) {
					TurnLeft();
					minDistToAim = left.distance(aimCell);
				}

				if (board.getCellState(NextCell()) != -1
						|| (Math.abs(dist) > 1.1 * TimerPeriod / speed)) {
					dist += 2 * Math.signum(direction) * TimerPeriod / speed;
					if (Math.abs(dist) >= 1) {
						if (board.getCellState(NextCell()) > 0
								&& board.getCellState(NextCell()) != 5) {
							Character player = null;
							for (Character character : characters) {
								if (character.id == board
										.getCellState(NextCell())) {
									player = character;
								}
							}
							player.speed = 0;
							player.dist = 0;
							player.cell = new Point(0, playersNum
									- catchedPlayers);
							catchedPlayers++;
							if (catchedPlayers == playersNum) {
								restarting = true;
							}
						}
						if (foodFlag)
							board.setCellState(cell, 5);
						else
							board.setCellState(cell, 0);

						cell = NextCell();

						if (board.getCellState(cell) == 5)
							foodFlag = true;
						else
							foodFlag = false;

						dist -= 2 * Math.signum(dist);
					}
				}

				board.setCellState(cell, this.id - 1);

				if (board.getCellState(DesiredCell()) != -1
						&& (Math.abs(dist) < 1.1 * TimerPeriod / speed)) {
					direction = desiredDirection;
				}
			}

			private void TurnRight() {
				switch (direction) {
				case 1:
					desiredDirection = 2;
					break;
				case -1:
					desiredDirection = -2;
					break;
				case 2:
					desiredDirection = -1;
					break;
				case -2:
					desiredDirection = 1;
					break;

				default:
					break;
				}
			}

			private void TurnLeft() {
				switch (direction) {
				case 1:
					desiredDirection = -2;
					break;
				case -1:
					desiredDirection = 2;
					break;
				case 2:
					desiredDirection = 1;
					break;
				case -2:
					desiredDirection = -1;
					break;

				default:
					break;
				}
			}

			private Point RightCell() {
				Point result = (Point) cell.clone();

				switch (direction) {
				case 1:
					result.x++;
					break;
				case -1:
					result.x--;
					break;
				case 2:
					result.y--;
					break;
				case -2:
					result.y++;
					break;

				default:
					break;
				}
				return result;
			}

			private Point LeftCell() {
				Point result = (Point) cell.clone();

				switch (direction) {
				case 1:
					result.x--;
					break;
				case -1:
					result.x++;
					break;
				case 2:
					result.y++;
					break;
				case -2:
					result.y--;
					break;

				default:
					break;
				}

				return result;
			}

		}

		public void ShowResults() {
			for (int i = 0; i < playersNum - catchedPlayers; i++) {
				Character max = characters.get(0);
				for (Character character : characters) {
					if (max.myFood <= character.myFood && character.speed != 0)
						max = character;
				}
				max.myFood = 0;
				max.speed = 0;
				max.dist = 0;
				max.cell = new Point(0, i);

			}
			restarting = true;
		}

	}

}
