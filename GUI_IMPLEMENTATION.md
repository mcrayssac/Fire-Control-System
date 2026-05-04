# Fire Control System (FCS) — GUI Implementation

Console interactive pour le **Fire Control System**. Cette interface offre un moyen ergonomique d'exécuter et de contrôler les différents modes du système de contrôle de tir pour véhicule blindé.

**Important** : L'implémentation GUI se trouve sur la branche `gui` du projet. Veuillez cloner le projet avec la commande spécifique indiquée ci-dessous.
Aucune GUI n'étant requise, ceci n'est que du bonus et offre simplement une manière plus moderne d'executer le projet 

## Prérequis

| Outil | Version |
|-------|---------|
| JDK | 21+ |
| Git | 2.0+ |

## Installation

### 1. Cloner le projet avec la branche GUI

```bash
git clone -b gui https://github.com/mcrayssac/Fire-Control-System.git FCS_GUI
cd FCS_GUI
```

## Utilisation

### Lancer l'application GUI

```bash
.\src\main\scala\fcs\gui\lancer.bat
```

Ou bien directement depuis l'explorateur de fichier avec un double clic

La console interactive se lance dans une nouvelle fenêtre permettant d'exécuter les différentes commandes et modes du projet :

## Architecture de la GUI

```
src/main/scala/fcs/
├── gui/                        # Implémentation GUI
│   ├── CommandConsoleWin.scala # Application console interactive
│   ├── lancer.bat              # Script de lancement (Windows)
│   └── README.txt              # Guide d'utilisation du console
├── actors/                     # 8 acteurs Akka Typed
│   ├── AmmoActor.scala
│   ├── CommandActor.scala
│   ├── FireControlActor.scala
│   ├── KafkaConsumerActor.scala
│   ├── KafkaProducerActor.scala
│   ├── SensorActor.scala
│   ├── SupervisorActor.scala
│   └── TrackingActor.scala
├── model/                      # Messages et états du système
│   ├── FCSState.scala
│   └── Messages.scala
├── kafka/                      # Configuration Kafka
│   ├── KafkaConfig.scala
│   └── Topics.scala
├── petri/                      # Modèle formel et vérification
│   ├── PetriNet.scala
│   ├── FCSPetriNet.scala
│   ├── StateSpaceAnalyzer.scala
│   ├── InvariantChecker.scala
│   ├── InvariantAnalysis.scala
│   ├── LTLVerifier.scala
│   ├── TraceComparator.scala
│   └── InteractiveSimulator.scala
└── Main.scala                  # Point d'entrée (akka-demo / conformance / live)
```

## Fonctionnalités principales

### Console interactive
L'application `CommandConsoleWin.scala` offre une interface console interactive permettant d'exécuter les différentes commandes du projet :

- **Compilation** : `sbt compile` — Compile le projet et ses dépendances
- **Tests** : `sbt test` — Lance les tests unitaires et la vérification formelle
- **Mode Akka-Demo** : `sbt "run akka-demo"` — Simulation du système avec Akka/Kafka (scénario nominal)
- **Mode Conformance** : `sbt "run conformance"` — Vérification de conformité Akka vs réseau de Petri
- **Mode Live Verbose** : `sbt "run live"` — Panneau interactif avec affichage détaillé
- **Mode Live Compact** : `sbt "run live compact"` — Panneau interactif en mode condensé

### Interaction avec la console

La console interactive permet :

- **Saisie utilisateur** : Champ de texte en bas de la fenêtre pour envoyer des commandes
- **Visualisation des logs** : Affichage en temps réel de la sortie standard du processus
- **Contrôle du processus** : Boutons pour arrêter le processus en cours ou effacer la console
- **Historique** : Suivi complet de l'exécution des commandes précédentes

### Architecture interne

L'implémentation repose sur :

- **CommandConsoleWin.scala** : Composant principal de l'interface utilisateur
- **Processus SBT** : Gestion des exécutions de commandes via subprocess
- **Communication bidirectionnelle** : Envoi de commandes et réception de la sortie standard/erreurs


Le script d'exécution gère automatiquement toutes les dépendances et configurations nécessaires.
## Ressources supplémentaires

- [Guide d'utilisation](src/main/scala/fcs/gui/README.txt) — Guide rapide de la console interactive
- [Rapports du projet](docs/rapport/) — Architecture et vérification formelle
- [Diagramme du réseau de Petri](docs/fcs_petri_net.drawio)
- [Documentation Akka](https://doc.akka.io/) — Framework d'acteurs utilisé
- [Documentation Scala](https://docs.scala-lang.org/) — Langage de programmation

## Supporté par

- **Branche** : `gui`
- **Dépôt** : https://github.com/mcrayssac/Fire-Control-System
- **Auteurs** : Arthur Neuez, Paul Pitiot, Maxime Crayssac
