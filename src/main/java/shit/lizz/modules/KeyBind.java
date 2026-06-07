package shit.lizz.modules;

import java.util.HashMap;
import java.util.Map;

public class KeyBind {
    private int keyCode;
    private static final Map<Integer, String> bindings = new HashMap<>();

    public KeyBind(int keyCode) {
        this.keyCode = keyCode;
    }

    public String getName() {
        if (this.keyCode == 0) {
            return "None";
        }
        return bindings.getOrDefault(this.keyCode, "Unknown");
    }

    public void setKey(int keyCode) {
        this.keyCode = keyCode;
    }

    public int getKey() {
        return this.keyCode;
    }

    static {
        bindings.put(-1, "None");
        bindings.put(32, "Space");
        bindings.put(39, "'");
        bindings.put(44, ",");
        bindings.put(45, "-");
        bindings.put(46, ".");
        bindings.put(47, "/");
        bindings.put(48, "0");
        bindings.put(49, "1");
        bindings.put(50, "2");
        bindings.put(51, "3");
        bindings.put(52, "4");
        bindings.put(53, "5");
        bindings.put(54, "6");
        bindings.put(55, "7");
        bindings.put(56, "8");
        bindings.put(57, "9");
        bindings.put(59, ";");
        bindings.put(61, "=");
        bindings.put(65, "A");
        bindings.put(66, "B");
        bindings.put(67, "C");
        bindings.put(68, "D");
        bindings.put(69, "E");
        bindings.put(70, "F");
        bindings.put(71, "G");
        bindings.put(72, "H");
        bindings.put(73, "I");
        bindings.put(74, "J");
        bindings.put(75, "K");
        bindings.put(76, "L");
        bindings.put(77, "M");
        bindings.put(78, "N");
        bindings.put(79, "O");
        bindings.put(80, "P");
        bindings.put(81, "Q");
        bindings.put(82, "R");
        bindings.put(83, "S");
        bindings.put(84, "T");
        bindings.put(85, "U");
        bindings.put(86, "V");
        bindings.put(87, "W");
        bindings.put(88, "X");
        bindings.put(89, "Y");
        bindings.put(90, "Z");
        bindings.put(91, "[");
        bindings.put(92, "\\");
        bindings.put(93, "]");
        bindings.put(96, "`");
        bindings.put(161, "W1");
        bindings.put(162, "W2");
        bindings.put(256, "Esc");
        bindings.put(257, "Enter");
        bindings.put(258, "Tab");
        bindings.put(259, "Bksp");
        bindings.put(260, "Ins");
        bindings.put(261, "Del");
        bindings.put(262, "Right");
        bindings.put(263, "Left");
        bindings.put(264, "Down");
        bindings.put(265, "Up");
        bindings.put(266, "PgUp");
        bindings.put(267, "PgDn");
        bindings.put(268, "Home");
        bindings.put(269, "End");
        bindings.put(280, "Caps");
        bindings.put(281, "Scroll");
        bindings.put(282, "NumLk");
        bindings.put(283, "PrtSc");
        bindings.put(284, "Pause");
        bindings.put(290, "F1");
        bindings.put(291, "F2");
        bindings.put(292, "F3");
        bindings.put(293, "F4");
        bindings.put(294, "F5");
        bindings.put(295, "F6");
        bindings.put(296, "F7");
        bindings.put(297, "F8");
        bindings.put(298, "F9");
        bindings.put(299, "F10");
        bindings.put(300, "F11");
        bindings.put(301, "F12");
        bindings.put(320, "KP 0");
        bindings.put(321, "KP 1");
        bindings.put(322, "KP 2");
        bindings.put(323, "KP 3");
        bindings.put(324, "KP 4");
        bindings.put(325, "KP 5");
        bindings.put(326, "KP 6");
        bindings.put(327, "KP 7");
        bindings.put(328, "KP 8");
        bindings.put(329, "KP 9");
        bindings.put(330, "KP .");
        bindings.put(331, "KP /");
        bindings.put(332, "KP *");
        bindings.put(333, "KP -");
        bindings.put(334, "KP +");
        bindings.put(335, "KP Enter");
        bindings.put(336, "KP =");
        bindings.put(340, "LShift");
        bindings.put(341, "LCtrl");
        bindings.put(342, "LAlt");
        bindings.put(343, "LSuper");
        bindings.put(344, "RShift");
        bindings.put(345, "RCtrl");
        bindings.put(346, "RAlt");
        bindings.put(347, "RSuper");
        bindings.put(348, "Menu");
    }
}