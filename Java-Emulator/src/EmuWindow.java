import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.image.BufferedImage;

public class EmuWindow extends JFrame implements KeyListener {

    private BufferedImage img;
    private int scale = 4;
    private JPanel screenPanel;

    public EmuWindow() {
        img = new BufferedImage(160, 144, BufferedImage.TYPE_INT_RGB);

        setTitle("Game Boy Emulator");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setResizable(false);

        screenPanel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (img != null) {
                    g.drawImage(img, 0, 0, 160 * scale, 144 * scale, null);
                }
            }
        };

        screenPanel.setPreferredSize(new Dimension(160 * scale, 144 * scale));
        add(screenPanel);
        pack();
        setLocationRelativeTo(null);

        addKeyListener(this);
        setFocusable(true);
        requestFocusInWindow();

        setVisible(true);
    }

    public void updateImage(int[] videoBuffer) {
        img.setRGB(0, 0, 160, 144, videoBuffer, 0, 160);
        screenPanel.repaint();
    }

    @Override
    public void keyTyped(KeyEvent e) {}

    @Override
    public void keyPressed(KeyEvent e) {
        switch (e.getKeyCode()) {
            case KeyEvent.VK_RIGHT: MemoryMapped_IO.gamepad.press(Gamepad.Button.RIGHT); break;
            case KeyEvent.VK_LEFT:  MemoryMapped_IO.gamepad.press(Gamepad.Button.LEFT); break;
            case KeyEvent.VK_UP:    MemoryMapped_IO.gamepad.press(Gamepad.Button.UP); break;
            case KeyEvent.VK_DOWN:  MemoryMapped_IO.gamepad.press(Gamepad.Button.DOWN); break;
            case KeyEvent.VK_Z:     MemoryMapped_IO.gamepad.press(Gamepad.Button.A); break;
            case KeyEvent.VK_X:     MemoryMapped_IO.gamepad.press(Gamepad.Button.B); break;
            case KeyEvent.VK_ENTER: MemoryMapped_IO.gamepad.press(Gamepad.Button.START); break;
            case KeyEvent.VK_SPACE: MemoryMapped_IO.gamepad.press(Gamepad.Button.SELECT); break;
        }
    }

    @Override
    public void keyReleased(KeyEvent e) {
        switch (e.getKeyCode()) {
            case KeyEvent.VK_RIGHT: MemoryMapped_IO.gamepad.release(Gamepad.Button.RIGHT); break;
            case KeyEvent.VK_LEFT:  MemoryMapped_IO.gamepad.release(Gamepad.Button.LEFT); break;
            case KeyEvent.VK_UP:    MemoryMapped_IO.gamepad.release(Gamepad.Button.UP); break;
            case KeyEvent.VK_DOWN:  MemoryMapped_IO.gamepad.release(Gamepad.Button.DOWN); break;
            case KeyEvent.VK_Z:     MemoryMapped_IO.gamepad.release(Gamepad.Button.A); break;
            case KeyEvent.VK_X:     MemoryMapped_IO.gamepad.release(Gamepad.Button.B); break;
            case KeyEvent.VK_ENTER: MemoryMapped_IO.gamepad.release(Gamepad.Button.START); break;
            case KeyEvent.VK_SPACE: MemoryMapped_IO.gamepad.release(Gamepad.Button.SELECT); break;
        }
    }
}