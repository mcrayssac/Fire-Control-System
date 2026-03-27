# 6. Mode live interactif

## 6.1 Objectif

Le mode `live` ajoute un **panneau de commande interactif** pour piloter le systeme pas a pas, observer l'etat courant, et distinguer clairement les actions **manuelles** (operateur) des actions **automatiques** (systeme).

Ce mode complete les executions `akka-demo` et `conformance` avec une vue orientee exploitation/formation.

---

## 6.2 Architecture technique

Le mode live repose sur deux points d'entree :

1. `Main.runInteractive(modeArg: Option[String])`
   - construit le reseau (`FCSPetriNet.build`)
   - choisit le mode d'affichage (`verbose` par defaut, `compact` optionnel)
   - delegue l'execution a `InteractiveSimulator.run`

2. `InteractiveSimulator`
   - gere la boucle interactive
   - classe les transitions (manuelles vs automatiques)
   - applique les delais simules
   - affiche l'etat systeme et les actions disponibles

---

## 6.3 Fonctionnalites ajoutees

### A) Modes d'affichage

- **Verbose (defaut)** :
  - journal detaille des actions systeme
  - detail des transitions automatiques
  - compte a rebours explicite avant tir

- **Compact (optionnel)** :
  - sortie plus concise
  - invite utilisateur simplifiee
  - message `Waiting for operator action...`

### B) Ergonomie de conduite

- Affichage structure en sections :
  - `STATUS`
  - `AUTO ACTIONS`
  - `MANUAL ACTIONS`
  - `INPUT`
- Commandes operateur integrees :
  - numero d'action
  - `status`
  - `help`
  - `reset`
  - `quit`

### C) Comportement fonctionnel

- Progression automatique des transitions non manuelles
- Delais simules par transition (chargement, autorisation, cooldown, etc.)
- Compte a rebours de tir :
  - `3`, `2`, `1`, `Fire`
- Avertissement stock faible deduplique (pas de spam identique)
- Suivi de cycles corrige (increment uniquement a retour Idle valide)

---

## 6.4 Commandes SBT associees

| Commande | Effet |
|---|---|
| `sbt "run live"` | Lance le panneau interactif en mode **verbose** (defaut). |
| `sbt "run live compact"` | Lance le panneau interactif en mode **compact**. |
| `sbt "run live verbose"` | Lance explicitement le mode **verbose**. |

---

## 6.5 Validation et tests

La fonctionnalite live est couverte par `InteractiveSimulatorSpec` :

- classification transitions manuelles/automatiques
- labels lisibles
- parsing mode de sortie (verbose/compact + valeurs inconnues)
- presence conditionnelle du message d'attente
- sequence du compte a rebours
- delais configures
- progression auto sur scenarios nominals et erreur
- cas limites (munitions nulles, cycles multiples)

Resultat attendu en CI/local : suite verte avec l'ensemble des tests du projet.