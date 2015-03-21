package myLib;

import java.io.Serializable;
import java.util.Vector;

public class GameState implements Serializable {
	private static final long serialVersionUID = -1056933580285366915L;
	public Vector<CharacterState> cs;
	public int[][] board;
	
	public GameState(Vector<CharacterState> cs, int[][] board) {
		this.cs = cs;
		this.board = new int[board.length][board[0].length];
		for (int i = 0; i < board.length; i++) {
			for (int j = 0; j < board[0].length; j++) {
				this.board[i][j] = board[i][j];
			}
		}
	}
}
