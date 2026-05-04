import javax.swing._
import javax.swing.border._
import javax.swing.text.{StyleConstants => SC}
import java.awt._
import java.awt.event._
import java.io._
import java.nio.charset.Charset
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

//  OS detection 
object OS {
  private val name = System.getProperty("os.name", "").toLowerCase
  val isWindows: Boolean = name.contains("win")
  val isMac:     Boolean = name.contains("mac")
  val isLinux:   Boolean = !isWindows && !isMac

  /** Execute a shell command string on the current OS */
  def shell(cmd: String): Array[String] =
    if (isWindows) Array("cmd.exe", "/c", cmd)
    else           Array("/bin/bash", "-c", cmd)

  /** Charset for reading process output */
  def processCharset: Charset =
    if (isWindows) {
      try Charset.forName("cp850")
      catch { case _: Exception => Charset.defaultCharset() }
    } else Charset.forName("UTF-8")

  def name2: String = if (isWindows) "Windows" else if (isMac) "macOS" else "Linux"
}

//  Fixed SBT commands 
object DefaultCmds {
  def all: ArrayBuffer[Cmd] = ArrayBuffer(
    Cmd("sbt compile",     "sbt compile",                              "Compile le projet (sources + dépendances)"),
    Cmd("sbt test",        "sbt test",                                 "Tests unitaires, invariants, LTL, analyse structurelle"),
    Cmd("akka-demo",       "sbt \"runMain fcs.Main akka-demo\"",     "Démo Akka/Kafka — scénario nominal"),
    Cmd("conformance",     "sbt \"runMain fcs.Main conformance\"",   "Vérification Akka vs modèle formel (Petri Net)"),
    Cmd("live",            "sbt \"runMain fcs.Main live\"",          "Panneau interactif (mode verbose)"),
    Cmd("live compact",    "sbt \"runMain fcs.Main live compact\"",  "Panneau interactif (mode compact)")
  )
}

//  Palette 
object Pal {
  val bg      = new Color(13,  16,  22)
  val surface = new Color(22,  26,  36)
  val border  = new Color(40,  46,  60)
  val accent  = new Color(72, 199, 142)
  val text    = new Color(210, 215, 225)
  val dim     = new Color(100, 110, 128)
  val red     = new Color(215,  75,  75)
  val yellow  = new Color(220, 165,  50)
  val blue    = new Color( 80, 155, 225)
  val cyan    = new Color( 70, 195, 200)
  val out     = new Color(195, 202, 214)
}

//  Command data 
case class Cmd(var label: String, var command: String, var desc: String = "")

//  ANSI-aware console 
class Console extends JTextPane {
  setBackground(new Color(10, 12, 18))
  setForeground(Pal.out)
  setFont(new Font("Monospaced", Font.PLAIN, 13))
  setEditable(false)
  setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10))

  private val doc = getStyledDocument

  private val ansiMap: Map[String, Option[Color]] = Map(
    "0"  -> None, "1"  -> None,
    "30" -> Some(Color.DARK_GRAY),
    "31" -> Some(Pal.red),
    "32" -> Some(new Color(90, 195, 100)),
    "33" -> Some(Pal.yellow),
    "34" -> Some(Pal.blue),
    "35" -> Some(new Color(175, 100, 205)),
    "36" -> Some(Pal.cyan),
    "37" -> Some(Pal.out)
  )

  def append(raw: String): Unit = SwingUtilities.invokeLater(new Runnable {
    def run(): Unit = {
      val pattern = "\u001b\\[([0-9;]*)m".r
      var cur: Color = Pal.out
      var bold = false
      var last = 0
      for (m <- pattern.findAllMatchIn(raw)) {
        if (m.start > last) write(raw.substring(last, m.start), cur, bold)
        m.group(1).split(";").foreach { c =>
          if (c == "0" || c.isEmpty) { cur = Pal.out; bold = false }
          else if (c == "1") bold = true
          else ansiMap.get(c).flatten.foreach(col => cur = col)
        }
        last = m.end
      }
      if (last < raw.length) write(raw.substring(last), cur, bold)
      setCaretPosition(doc.getLength)
    }
  })

  private def write(text: String, color: Color, bold: Boolean): Unit = {
    val s = doc.addStyle(null, null)
    SC.setForeground(s, color); SC.setBold(s, bold)
    doc.insertString(doc.getLength, text, s)
  }

  def clear(): Unit = SwingUtilities.invokeLater(new Runnable {
    def run(): Unit = setText("")
  })
}

//  Process runner (cross-platform) 
class Runner(onOut: String => Unit, onDone: Int => Unit) {
  @volatile private var proc:  Option[Process]      = None
  @volatile private var stdin: Option[PrintWriter]   = None
  private val ESC = "\u001b"

  def running: Boolean = proc.exists(_.isAlive)

  def run(cmd: String): Unit = {
    if (running) { onOut(s"${ESC}[33m[BUSY] Une commande est déjà en cours. Arrêtez-la d'abord.${ESC}[0m\n"); return }
    onOut(s"${ESC}[36m▶ $cmd${ESC}[0m\n")
    Future {
      try {
        val pb = new ProcessBuilder(OS.shell(cmd): _*)
        val workDir = Option(System.getProperty("app.workdir"))
          .map(new File(_))
          .filter(_.isDirectory)
          .getOrElse(new File(System.getProperty("user.dir")))
        pb.directory(workDir)
        pb.redirectErrorStream(true)
        // On Windows, force UTF-8 output for some commands
        if (OS.isWindows) pb.environment().put("PYTHONIOENCODING", "utf-8")
        val p = pb.start()
        proc  = Some(p)
        stdin = Some(new PrintWriter(
          new BufferedWriter(new OutputStreamWriter(p.getOutputStream, OS.processCharset)), true))
        val br = new BufferedReader(new InputStreamReader(p.getInputStream, OS.processCharset))
        var line = br.readLine()
        while (line != null) { onOut(line + "\n"); line = br.readLine() }
        val code = p.waitFor()
        proc = None; stdin = None
        onDone(code)
      } catch { case e: Exception =>
        val msg = Option(e.getMessage).getOrElse(e.getClass.getName)
        onOut(s"${ESC}[31m[ERREUR] $msg${ESC}[0m\n")
        proc = None; stdin = None; onDone(-1)
      }
    }
  }

  def send(text: String): Unit = stdin.foreach(_.println(text))

  def kill(): Unit = {
    proc.foreach { p =>
      // On Windows, also kill child processes
      if (OS.isWindows) {
        try new ProcessBuilder("taskkill", "/F", "/T", "/PID", p.pid().toString).start()
        catch { case _: Exception => p.destroyForcibly() }
      } else p.destroyForcibly()
      onOut(s"${ESC}[33m[ARRÊT] Processus terminé.${ESC}[0m\n")
    }
    proc = None; stdin = None
  }
}

//  UI helpers 
object UI {
  def btn(label: String, col: Color, action: => Unit): JButton = {
    val b = new JButton(label)
    b.setBackground(dim(col, 3)); b.setForeground(col)
    b.setFont(new Font("SansSerif", Font.BOLD, 12))
    b.setFocusPainted(false)
    b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))
    b.setBorder(BorderFactory.createCompoundBorder(
      BorderFactory.createLineBorder(col, 1, true),
      BorderFactory.createEmptyBorder(7, 14, 7, 14)))
    b.addMouseListener(new MouseAdapter {
      override def mouseEntered(e: MouseEvent): Unit = b.setBackground(dim(col, 2))
      override def mouseExited (e: MouseEvent): Unit = b.setBackground(dim(col, 3))
    })
    b.addActionListener(new ActionListener {
      def actionPerformed(e: ActionEvent): Unit = action
    })
    b
  }

  def dim(c: Color, d: Int) = new Color(c.getRed / d, c.getGreen / d, c.getBlue / d)

  def lbl(text: String, col: Color, size: Int, bold: Boolean = false): JLabel = {
    val l = new JLabel(text)
    l.setForeground(col)
    l.setFont(new Font("Monospaced", if (bold) Font.BOLD else Font.PLAIN, size))
    l
  }

  def textField(): JTextField = {
    val f = new JTextField()
    f.setBackground(new Color(28, 33, 44)); f.setForeground(Pal.text)
    f.setCaretColor(Pal.accent)
    f.setFont(new Font("Monospaced", Font.PLAIN, 13))
    f.setBorder(BorderFactory.createCompoundBorder(
      BorderFactory.createLineBorder(Pal.border),
      BorderFactory.createEmptyBorder(5, 8, 5, 8)))
    f
  }

  def namedField(cols: Int): JTextField = {
    val f = new JTextField(cols)
    f.setBackground(new Color(28, 33, 44)); f.setForeground(Pal.text)
    f.setCaretColor(Pal.accent)
    f.setFont(new Font("Monospaced", Font.PLAIN, 13))
    f.setBorder(BorderFactory.createCompoundBorder(
      BorderFactory.createLineBorder(Pal.border),
      BorderFactory.createEmptyBorder(5, 8, 5, 8)))
    f
  }
}

//  Edit dialog 
class EditDialog(parent: JFrame, init: Option[Cmd]) extends JDialog(parent, "Éditeur de commande", true) {
  var result: Option[Cmd] = None
  private val labelF   = UI.namedField(18); init.foreach(c => labelF.setText(c.label))
  private val commandF = UI.namedField(36); init.foreach(c => commandF.setText(c.command))
  private val descF    = UI.namedField(36); init.foreach(c => descF.setText(c.desc))

  locally {
    getContentPane.setBackground(Pal.surface)
    val body = new JPanel(new GridLayout(3, 1, 0, 8))
    body.setBackground(Pal.surface)
    body.setBorder(BorderFactory.createEmptyBorder(16, 16, 8, 16))

    def row(name: String, f: JTextField): JPanel = {
      val p = new JPanel(new BorderLayout(10, 0)); p.setBackground(Pal.surface)
      val lbl = new JLabel(name); lbl.setForeground(Pal.dim)
      lbl.setFont(new Font("SansSerif", Font.PLAIN, 12))
      lbl.setPreferredSize(new Dimension(80, 0))
      p.add(lbl, BorderLayout.WEST); p.add(f, BorderLayout.CENTER); p
    }
    body.add(row("Label",    labelF))
    body.add(row("Commande", commandF))
    body.add(row("Desc",     descF))

    val saveBtn   = UI.btn("  Sauver  ", Pal.accent, {
      if (labelF.getText.nonEmpty && commandF.getText.nonEmpty) {
        result = Some(Cmd(labelF.getText.trim, commandF.getText.trim, descF.getText.trim))
        dispose()
      } else JOptionPane.showMessageDialog(this,
        "Le label et la commande sont obligatoires.", "Validation", JOptionPane.WARNING_MESSAGE)
    })
    val cancelBtn = UI.btn(" Annuler ", Pal.dim, dispose())

    val btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8))
    btns.setBackground(Pal.surface)
    btns.add(cancelBtn); btns.add(saveBtn)
    add(body, BorderLayout.CENTER); add(btns, BorderLayout.SOUTH)
    getRootPane.setDefaultButton(saveBtn)
    pack(); setLocationRelativeTo(parent)
  }
}

//  WrapLayout 
class WrapLayout(align: Int, hgap: Int, vgap: Int) extends FlowLayout(align, hgap, vgap) {
  override def preferredLayoutSize(t: Container): Dimension = calc(t, pref = true)
  override def minimumLayoutSize (t: Container): Dimension  = calc(t, pref = false)
  private def calc(t: Container, pref: Boolean): Dimension = t.getTreeLock.synchronized {
    val maxW = { val w = t.getSize.width; if (w == 0) Int.MaxValue else w - t.getInsets.left - t.getInsets.right - getHgap * 2 }
    var rowW = 0; var rowH = 0; var totW = 0; var totH = 0
    for (i <- 0 until t.getComponentCount) {
      val m = t.getComponent(i)
      if (m.isVisible) {
        val d = if (pref) m.getPreferredSize else m.getMinimumSize
        if (rowW + d.width > maxW && rowW > 0) { totW = math.max(totW, rowW); totH += rowH + getVgap; rowW = 0; rowH = 0 }
        rowW += d.width + getHgap; rowH = math.max(rowH, d.height)
      }
    }
    totW = math.max(totW, rowW); totH += rowH + getVgap * 2
    val ins = t.getInsets
    new Dimension(totW + ins.left + ins.right + getHgap * 2, totH + ins.top + ins.bottom)
  }
}

// Main
object CommandConsoleWin extends App {
  // On Windows, enable font anti-aliasing
  System.setProperty("awt.useSystemAAFontSettings", "on")
  System.setProperty("swing.aatext", "true")
  // Use cross-platform L&F for consistent dark theme on all OS
  UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName)

  SwingUtilities.invokeLater(new Runnable { def run(): Unit = buildUI() })

  def buildUI(): Unit = {
    val cmds = DefaultCmds.all
    val con  = new Console()
    val ESC  = "\u001b"

    val statusLabel = UI.lbl(s"  ● Prêt  [${OS.name2}]", Pal.accent, 12, bold = true)

    val runner = new Runner(
      onOut  = line => con.append(line),
      onDone = (code: Int) => {
        val (escCode, sym) = if (code == 0) ("32", "✔") else ("31", "✘")
        con.append(s"${ESC}[${escCode}m[$sym] Code de retour : $code${ESC}[0m\n\n")
        SwingUtilities.invokeLater(new Runnable { def run(): Unit = {
          statusLabel.setForeground(Pal.accent)
          statusLabel.setText(s"  ● Prêt  [${OS.name2}]")
        }})
      }
    )

    val btnPanel = new JPanel(new WrapLayout(FlowLayout.LEFT, 8, 8))
    btnPanel.setBackground(Pal.surface)

    var frame: JFrame = null

    def refreshBtns(): Unit = {
      btnPanel.removeAll()
      cmds.foreach { cmd =>
        val b = UI.btn(cmd.label, Pal.accent, {
          if (runner.running) {
            con.append(s"${ESC}[33m[OCCUPÉ] Arrêtez d'abord la commande en cours.${ESC}[0m\n")
          } else {
            statusLabel.setForeground(Pal.yellow)
            statusLabel.setText(s"  ▶ ${cmd.label}")
            runner.run(cmd.command)
          }
        })
        val tip = if (cmd.desc.nonEmpty) s"${cmd.command}  —  ${cmd.desc}" else cmd.command
        b.setToolTipText(tip)
        btnPanel.add(b)
      }
      btnPanel.revalidate(); btnPanel.repaint()
    }

    //  Stdin input 
    val inputField = UI.textField()
    inputField.setForeground(Pal.accent)
    inputField.setPreferredSize(new Dimension(0, 36))

    val sendBtn = UI.btn("Envoyer ↵", Pal.blue, {
      val t = inputField.getText.trim
      if (t.nonEmpty) {
        con.append(s"${ESC}[36m> $t${ESC}[0m\n")
        runner.send(t)
        inputField.setText("")
      }
    })
    inputField.addKeyListener(new KeyAdapter {
      override def keyPressed(e: KeyEvent): Unit = if (e.getKeyCode == KeyEvent.VK_ENTER) sendBtn.doClick()
    })

    val stdinRow = new JPanel(new BorderLayout(6, 0))
    stdinRow.setBackground(Pal.bg)
    stdinRow.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0))
    stdinRow.add(UI.lbl(" stdin › ", Pal.accent, 13, bold = true), BorderLayout.WEST)
    stdinRow.add(inputField, BorderLayout.CENTER)
    stdinRow.add(sendBtn, BorderLayout.EAST)

    //  Toolbar 
    val killBtn  = UI.btn("⬛ Arrêter", Pal.red,    runner.kill())
    val clearBtn = UI.btn("⌫ Effacer", Pal.dim,    con.clear())

    val toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6))
    toolbar.setBackground(Pal.bg)
    toolbar.add(killBtn); toolbar.add(clearBtn)
    toolbar.add(Box.createHorizontalStrut(18))
    toolbar.add(statusLabel)

    val btnScroll = new JScrollPane(btnPanel,
      ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
      ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER)
    btnScroll.setBackground(Pal.surface)
    btnScroll.setBorder(BorderFactory.createTitledBorder(
      BorderFactory.createLineBorder(Pal.border), "  Commandes  ",
      TitledBorder.LEFT, TitledBorder.TOP,
      new Font("SansSerif", Font.BOLD, 11), Pal.dim))
    btnScroll.setPreferredSize(new Dimension(0, 125))

    val headerLabel = UI.lbl("  ⌫  SBT Console", Pal.accent, 16, bold = true)
    headerLabel.setBackground(Pal.surface); headerLabel.setOpaque(true)
    headerLabel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12))

    val conScroll = new JScrollPane(con)
    conScroll.setBorder(BorderFactory.createLineBorder(Pal.border))

    val conPanel = new JPanel(new BorderLayout())
    conPanel.setBackground(Pal.bg)
    conPanel.add(conScroll, BorderLayout.CENTER)
    conPanel.add(stdinRow, BorderLayout.SOUTH)

    val top = new JPanel(new BorderLayout())
    top.setBackground(Pal.bg)
    top.add(toolbar, BorderLayout.NORTH)
    top.add(btnScroll, BorderLayout.CENTER)

    val root = new JPanel(new BorderLayout())
    root.setBackground(Pal.bg)
    root.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8))
    root.add(top, BorderLayout.NORTH)
    root.add(conPanel, BorderLayout.CENTER)

    frame = new JFrame(s"  SBT Console  [${OS.name2}]")
    frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE)
    frame.setPreferredSize(new Dimension(1020, 720))
    frame.getContentPane.setBackground(Pal.bg)
    frame.add(headerLabel, BorderLayout.NORTH)
    frame.add(root, BorderLayout.CENTER)

    refreshBtns()

    con.append(
      "\u001b[32m╔══════════════════════════════════════════════════════════╗\u001b[0m\n" +
      "\u001b[32m║     Scala Command Console  ·  prêt                       ║\u001b[0m\n" +
      "\u001b[32m╚══════════════════════════════════════════════════════════╝\u001b[0m\n\n"
    )
    con.append("\u001b[36mClic sur un bouton pour lancer la commande sbt correspondante.\u001b[0m\n")
    con.append("\u001b[36mChamp stdin > pour interagir avec sbt si besoin (ex: ENTREE pour continuer akka-demo).\u001b[0m\n\n")

    frame.pack()
    frame.setLocationRelativeTo(null)
    frame.setVisible(true)
  }
}
