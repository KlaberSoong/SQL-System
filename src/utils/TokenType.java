package utils;

/**
 * 词法单元种别（种别码），对应四元式 [种别码, 词素值, 行号, 列号]。
 */
public enum TokenType {
    KEYWORD, IDENTIFIER, CONST, OPERATOR, DELIMITER;
}
