package serv;

import java.awt.Point;
import java.awt.geom.Point2D;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Vector;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import myLib.*;

public class Server {

	private ServerSocket ss; // ��� ������-�����
	private Thread serverThread; // ������� ���� ��������� ������-������
	private int port; // ���� ������ ������.
	// �������, ��� ��������� ��� SocketProcessor� ��� ��������
	BlockingQueue<User> q = new LinkedBlockingQueue<User>();
	BlockingQueue<Room> rooms = new LinkedBlockingQueue<Room>();

	public static void main(String[] args) throws IOException {
		new Server(6666).run();
	}

	public Server(int port) throws IOException {

		ss = new ServerSocket(port); // ������� ������-�����
		this.port = port; // ��������� ����.

		initialize();

	}

	private void initialize() {

	}

	/**
	 * ������� ���� �������������/�������� ��������.
	 */
	void run() {
		serverThread = Thread.currentThread(); // �� ������ ��������� ����
												// (����� ����� �� ����
												// interrupt())
		while (true) {
			Socket s = getNewConn(); // �������� ����� ���������� ���
										// ����-���������
			if (serverThread.isInterrupted()) { // ���� ��� ����-����������, ��
												// ���� ���� ���� interrupted(),
				// ���� ����������
				break;
			} else if (s != null) { // "������ ���� ������� ������� ������"...
				try {
					final User processor = new User(s); // �������
														// �����-���������
					final Thread thread = new Thread(processor); // �������
																	// ���������
																	// �����������
																	// ����
																	// ������
																	// �� ������
					thread.setDaemon(true); // ������ �� � ������ (����� ��
											// ������� �� ��������)
					thread.start(); // ���������
					q.offer(processor); // ��������� � ������ ��������
										// �����-�����������
					System.out.println(s.toString() + " connected");
				} // ��� ������ � �������. ���� ������� ������� (new
					// SocketProcessor()) ����������,
					// �� ��������� ������ �������, ���� ��������� �� �����, �
					// ������ �� ��������
				catch (IOException ignored) {
				} // ���� �� ���������� �������� �������� ��� �� ���������.
			}
		}
	}

	/**
	 * ������� ����� �����������.
	 * 
	 * @return ����� ������ �����������
	 */
	private Socket getNewConn() {
		Socket s = null;
		try {
			s = ss.accept();
		} catch (IOException e) {
			shutdownServer(); // ���� ������ � ������ ������ - "�����" ������
		}
		return s;
	}

	/**
	 * ����� "��������" �������
	 */
	private synchronized void shutdownServer() {
		// ������������ ������ ������� ���������, ��������� ������
		for (User s : q) {
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
		Socket s;
		Socket ds; // data socket
		private InputStream sin;
		private OutputStream sout;
		ObjectOutputStream dObjOut;
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
							close(); // ���� �� ���������� - ��������� �����.
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
				// ���� ����� �� ������...
				String line = null;

				DataInputStream in = new DataInputStream(sin);

				try {
					line = in.readUTF();
					System.out
							.println("The dumb client just sent me this line : "
									+ line);
				} catch (IOException e) {
					close(); // ���� �� ���������� - ��������� �����.
				}

				if (line == null) { // ���� ������ ���������� � �������
									// ������.
					close(); // �� ��������� �����
				} else if ("shutdown".equals(line)) { // ���� ��������� �������
														// "�������� ������",
														// ��...
					serverThread.interrupt(); // ������� �������� ���� �
												// �������� ���� � �������������
												// ����������.
					try {
						new Socket("localhost", port); // ������� ����-�������
														// (����� ����� ��
														// .accept())
					} catch (IOException ignored) { // ������ �����������
					} finally {
						shutdownServer(); // � ����� ������ ������ ������� ���
											// ������ shutdownServer().
					}
				} else if ("RefreshRoomList".equals(line)) {
					SendRoomList();
				} else if (line.contains("CreateRoom:")) {
					CreateRoom(line);
				} else if (line.contains("EnterRoom:")) {
					EnterRoom(line);
				} else if (line.contains("LeaveRoom")) {
					LeaveRoom();
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
			for (Room room : rooms) {
				if (room.name.equals(myRoomName)) {
					room.RemoveUser(this);
					myRoomName = "";
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
					if (room.currPlayers < room.maxPlayers) {
						room.AddPlayer(this);
						myRoomName = name;
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
					out.writeUTF(list.substring(1)); // �������� ������� �������
														// �� �����
				// ������ ������.
				out.flush(); // ���������� ����� ��������� �������� ������.
			} catch (IOException e) {
				close(); // ���� ���� � ������ �������� - ��������� ������
							// �����.
			}
		}

		public synchronized void close() {
			if (!this.myRoomName.isEmpty()) {
				for (Room room : rooms) {
					if (room.name.equals(this.myRoomName)) {
						room.RemoveUser(this);
					}
				}
			}
			q.remove(this);
			if (!s.isClosed()) {
				try {
					s.close();
					System.out.println(s.toString() + " disconnected");
				} catch (IOException ignored) {
				}
			}
			if (!ds.isClosed()) {
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

		public void SendState(Vector<CharacterState> state) {
			try {
				dObjOut.writeObject(state);
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
		private BlockingQueue<User> users = new LinkedBlockingQueue<User>();
		private Timer timer = new java.util.Timer();

		private TimerTask task = new TimerTask() {
			public void run() {
				SendState();

			}
		};

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
			users.offer(firstPlayer);

			game = new Game(maxPlayers);

			if (currPlayers == maxPlayers)
				StartGame();
		}

		public void AddPlayer(User firstPlayer) {
			if (currPlayers < maxPlayers) {
				users.offer(firstPlayer);
				currPlayers = users.size();
				if (currPlayers == maxPlayers)
					StartGame();
			}
		}

		private void StartGame() {
			int i = 1;
			for (User user : users) {
				user.playerId = i++;
				user.SendStart();
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
			Vector<CharacterState> cs = game.getGameState();
			for (User user : users) {
				user.SendState(cs);
			}
		}

		private void SendBoard() {
			for (User user : users) {
				user.SendBoard(game.board.board);
			}
		}

		public void RemoveUser(User user) {
			users.remove(user);
			currPlayers = users.size();
			if (currPlayers == 0) {
				rooms.remove(this);
			}
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
				{ -1, -1, -1, -1, -1, -1, 0, -1, -1, 0, -1, -1, -1, -2, -2, -1,
						-1, -1, 0, -1, -1, 0, -1, -1, -1, -1, -1, -1 },
				{ -1, -1, -1, -1, -1, -1, 0, -1, -1, 0, -1, 0, 0, 0, 0, 0, 0,
						-1, 0, -1, -1, 0, -1, -1, -1, -1, -1, -1 },
				{ 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, 0, 0, 0, 0, 0, 0, -1, 0, 0,
						0, 0, 0, 0, 0, 0, 0, 0 },
				{ -1, -1, -1, -1, -1, -1, 0, -1, -1, 0, -1, 0, 0, 0, 0, 0, 0,
						-1, 0, -1, -1, 0, -1, -1, -1, -1, -1, -1 },
				{ -1, -1, -1, -1, -1, -1, 0, -1, -1, 0, -1, -1, -1, -1, -1, -1,
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
		private final int DefaultSpeed = 300;
		private final int TimerPeriod = 30;

		int playersNum, ghostsNum = 4;
		Board board;
		Vector<Character> characters = new Vector<Character>();
		Timer timer = new java.util.Timer();

		TimerTask task = new TimerTask() {
			public void run() {
				for (Character character : characters) {
					character.move();
				}
			}
		};

		public void GoUp(int id){
			for (Character ch : characters) {
				if (ch.id == id) {
					ch.setDesiredDirection(-2);
				}
			}
		}
		public void GoDown(int id){
			for (Character ch : characters) {
				if (ch.id == id) {
					ch.setDesiredDirection(2);
				}
			}
		}
		public void GoLeft(int id){
			for (Character ch : characters) {
				if (ch.id == id) {
					ch.setDesiredDirection(-1);
				}
			}
		}
		public void GoRight(int id){
			for (Character ch : characters) {
				if (ch.id == id) {
					ch.setDesiredDirection(1);
				}
			}
		}
		
		public Game(int playersNum) {

			this.playersNum = playersNum;
			initialize();

		}

		private void initialize() {

			board = new DefaultBoard();
			
			for (int i = 0; i < playersNum; i++) {
				int direction = 1; // "1" - rigth, "-1" - left,
									// "2" - down, "-2" - up
				if (i % 2 == 1)
					direction = -1;
				characters.add(new Player(defaultPlayersStartPoints[i],
						direction, DefaultSpeed, i + 1));
				board.setCellState(defaultPlayersStartPoints[i], 1);
			}
			for (int i = 0; i < ghostsNum; i++) {
				int direction = 1;
				if (i % 2 == 1)
					direction = -1;
				characters.add(new Ghost(defaultGhostsStartPoints[i],
						direction, DefaultSpeed, -1 - i, characters.get(i % playersNum)));
				board.setCellState(defaultGhostsStartPoints[i], -2);
			}
		}

		@Override
		public void run() {
			timer.schedule(task, 0, TimerPeriod);
			// timer.cancel();
		}

		private class Board {
			protected int board[][];

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

		private class DefaultBoard extends Board {
			public DefaultBoard() {
				board = defaultBoard.clone();
			}
		}

		protected Vector<CharacterState> getGameState() {
			Vector<CharacterState> cs = new Vector<CharacterState>();
			for (Character character : characters) {
				cs.add(character.getCharState());
			}
			return cs;
		}

		abstract class Character {
			protected int id;
			protected Point cell;
			protected double dist = 0;
			protected int direction;
			protected int speed;
			protected int desiredDirection;

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
			 *            "1" - rigth, "-1" - left, "2" - down, "-2" - up
			 * @param speed
			 *            Time in ms needed to reach next cell
			 */
			public Character(Point cell, int direction, int speed, int id) {
				this.cell = (Point) cell.clone();
				this.direction = direction;
				this.desiredDirection = direction;
				this.speed = speed;
				this.id = id;
			}

			public void setDesiredDirection(int dd) {
				desiredDirection = dd;
			}
			
			public void move() {
				if (board.getCellState(NextCell()) != -1
						|| (Math.abs(dist)>1.1*TimerPeriod / speed)) {
					dist += 2 * Math.signum(direction) * TimerPeriod / speed;
					if (Math.abs(dist) >= 1) {
						board.setCellState(cell, 0);
						board.setCellState(NextCell(), 1);
						cell = NextCell();
						dist -= 2 * Math.signum(dist);

					}
				}
			}

			protected Point DesiredCell() {
				Point result = (Point) cell.clone();
				
				switch (desiredDirection) {
				case 1:
					result.y++;
					if (result.y == 27) result.y = 1;
					break;
				case -1:
					result.y--;
					if (result.y == -1) result.y = 27;
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
					if (result.y == 27) result.y = 1;
					break;
				case -1:
					result.y--;
					if (result.y == -1) result.y = 27;
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
			 *            "1" - rigth, "-1" - left, "2" - down, "-2" - up
			 * @param speed
			 *            Time in ms needed to reach next cell
			 */
			public Player(Point cell, int direction, int speed, int id) {
				super(cell, direction, speed, id);
			}
			
			@Override
			public void move() {
				if (Math.abs(desiredDirection) == Math.abs(direction))
					direction = desiredDirection;
				
				super.move();
				
				if (board.getCellState(DesiredCell()) != -1
						&& (Math.abs(dist)<1.1*TimerPeriod / speed)){
					direction = desiredDirection;
				}
			}
			
		}

		private class Ghost extends Character {
			private Character aim;
			/**
			 * @param direction
			 *            "1" - rigth, "-1" - left, "2" - down, "-2" - up
			 * @param speed
			 *            Time in ms needed to reach next cell
			 */
			public Ghost(Point cell, int direction, int speed, int id, Character character) {
				super(cell, direction, speed, id);
				this.aim = character;
			}

			@Override
			public void move() {

				Point right = RightCell();
				Point left = LeftCell();
				Point next = NextCell();
				
				double minDistToAim = Double.MAX_VALUE;
				
				if (board.getCellState(next) != -1 && 
						next.distance(aim.cell) < minDistToAim){
					desiredDirection = direction;
					minDistToAim = next.distance(aim.cell);
				}
				if (board.getCellState(right) != -1 && 
						right.distance(aim.cell) < minDistToAim){
					TurnRight();
					minDistToAim = right.distance(aim.cell);
				}
				if (board.getCellState(left) != -1 && 
						left.distance(aim.cell) < minDistToAim){
					TurnLeft();
					minDistToAim = left.distance(aim.cell);
				}	
				
				super.move();
				
				if (board.getCellState(DesiredCell()) != -1
						&& (Math.abs(dist)<1.1*TimerPeriod / speed)){
					direction = desiredDirection;
				}
			}
			
			private void TurnRight(){
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
			
			private void TurnLeft(){
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

	}

}
