// Prova che l'orario compresso per l'iPhone si apre come fa l'app:
//   swift strumenti/prova-deflate.swift orario.db.deflate orario.db
import Foundation

let argomenti = CommandLine.arguments
guard let compresso = NSData(contentsOfFile: argomenti[1]) else {
    print("orario compresso per iPhone: non trovo \(argomenti[1])")
    exit(1)
}
let aperto = try compresso.decompressed(using: .zlib) as Data
let originale = try Data(contentsOf: URL(fileURLWithPath: argomenti[2]))
if aperto == originale {
    print("orario compresso per iPhone: si apre, \(compresso.length) -> \(aperto.count) byte, identico")
} else {
    print("orario compresso per iPhone: DIVERSO dall'originale (\(aperto.count) contro \(originale.count) byte)")
    exit(1)
}
