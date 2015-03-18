package myLib;

import java.awt.Point;
import java.io.Serializable;

public class CharacterState implements Serializable {
	private static final long serialVersionUID = 7237905012931057864L;
	public int id;
	public Point cell;
	public double dist;
	public int direction, speed;
}
