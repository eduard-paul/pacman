package myLib;

import java.io.Serializable;
import java.util.Vector;

public class GameState implements Serializable {
	private static final long serialVersionUID = -1056933580285366915L;
	Vector<CharacterState> cs;
	int[][] board;
	
	public GameState(Vector<CharacterState> cs, int[][] board) {
		this.cs = cs;
		this.board = board;
	}
}
