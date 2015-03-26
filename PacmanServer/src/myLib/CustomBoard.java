package myLib;

import java.awt.Point;
import java.io.Serializable;
import java.util.concurrent.BlockingQueue;

public class CustomBoard implements Serializable{
	private static final long serialVersionUID = -954141462175665059L;
	
	public int[][] board;		
	public BlockingQueue<Point> playersStartPoints;
	public BlockingQueue<Point> ghostsStartPoints;
	
}
