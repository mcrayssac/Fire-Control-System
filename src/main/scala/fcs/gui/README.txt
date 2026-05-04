╔══════════════════════════════════════════════════════╗
║          SBT Console  —  Guide d'utilisation         ║
╚══════════════════════════════════════════════════════╝

LANCEMENT
─────────
  Windows :       Double-cliquer sur  lancer.bat

COMMANDES DISPONIBLES
─────────────────────
  [ sbt compile ]    Compile le projet
  [ sbt test ]       Tests unitaires + vérification formelle
  [ akka-demo ]      sbt "run akka-demo"  — scénario nominal
  [ conformance ]    sbt "run conformance" — rapport Petri Net
  [ live ]           sbt "run live" — panneau interactif verbose
  [ live compact ]   sbt "run live compact" — mode compact

INTERAGIR AVEC LE PROCESSUS
────────────────────────────
  Pendant l'exécution d'une commande, utilisez le champ
  "stdin ›" en bas pour envoyer du texte au processus
  (ex : appuyer sur ENTRÉE pour continuer akka-demo).

  [ Arrêter ]  →  Force l'arrêt du processus en cours
  [ Effacer ]  →  Vide la console
