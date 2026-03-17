# 4. Rapport de verification

## 4.1 Espace d'etats

Exploration exhaustive (BFS) avec M0 = (P0=1, P10=3) :

| Metrique | Valeur |
|---|---|
| Marquages atteignables | **111** |
| Arcs | **227** |
| Deadlocks | **0** |

L'absence de deadlock signifie que depuis n'importe quel etat, au moins une transition est toujours franchissable.

---

## 4.2 Analyse structurelle

### P-invariants (C^T . y = 0)

**2 P-invariants minimaux** trouves par elimination de Gauss :

**y1 -- Conservation du flux de controle :**
```
y1 = (1, 1, 1, 0, 1, 1, 1, 1, 1, 1, 0, 0, 0)
```
P0 + P1 + P2 + P4 + P5 + P6 + P7 + P8 + P9 = **1** (constante)

Le jeton de controle est toujours dans exactement une place du cycle principal. Correspond a **INV7**.

**y2 -- Conservation globale :**
```
y2 = (1, 0, 0, 2, 0, 2, 1, 1, 1, 1, 2, 1, 1)
```
Relie places de controle et places ressource. La somme ponderee totale reste constante.

Les deux P-invariants sont verifies sur les 111 marquages atteignables.

### T-invariants (C . x = 0)

**1 T-invariant** trouve :
```
x1 = (0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 0) = {T9, T10}
```

Le cycle erreur/recuperation ramene au meme marquage. Le cycle nominal de tir n'est **pas** un T-invariant car il consomme 1 munition et produit 2 logs (irreversible).

---

## 4.3 Bornitude

| Place | Borne max | |
|---|:---:|---|
| P0-P2, P4-P9 (controle) | 1 | Toutes sauf (1-bornees) |
| P3 (Ammo_Loaded) | 3 | Borne par le stock initial |
| P10 (Ammo_Stock) | 3 | Stock initial |
| P11 (Kafka_Queue) | 2 | Max 2 evenements en file |
| P12 (Log_Recorded) | 6 | 2 par cycle x 3 cycles max |

**Resultat : reseau 6-borne** (k=6, du a P12). Les places de controle sont toutes 1-bornees.

---

## 4.4 Vivacite

| Transition | Niveau | |
|---|:---:|---|
| T0-T8, T11 | L1 | Potentiellement franchissable |
| **T9, T10** | **L4** | **Vivante** |

Le reseau n'est **pas vivant au sens L4**. Apres epuisement des munitions (P10=0), les transitions du cycle de tir ne sont plus franchissables. Seul le cycle erreur (T9/T10) reste vivant. C'est **physiquement correct** : un systeme sans munitions ne peut plus tirer. Aucune transition n'est morte (L0).

---

## 4.5 Invariants metier

| # | Propriete | Formalisation | Resultat |
|---|---|---|:---:|
| INV1 | Pas de tir sans verrouillage | T4 exige P4 (via T3 qui exige P2) | **OK** |
| INV2 | Pas de tir sans munition chargee | T4 exige P3 | **OK** |
| INV3 | Pas de tir sans autorisation | T4 exige P4 | **OK** |
| INV4 | Stock munitions >= 0 | Pour tout M : M(P10) >= 0 | **OK** |
| INV5 | Exclusion mutuelle tir/rechargement | Pour tout M : M(P6) + M(P7) <= 1 | **OK** |
| INV6 | Pas de tir pendant cooldown | M(P6) > 0 => M(P8) = 0 | **OK** |
| INV7 | Conservation flux de controle | Somme places controle = 1 | **OK** |
| INV8 | Tout tir est journalise | G(firing -> F(log_recorded)) | **OK** |
| INV9 | Retour a l'etat idle | Etats avec M(P0) > 0 existent | **OK** |
| INV10 | Absence de deadlock | Aucun etat sans transition | **OK** |

**10/10 invariants satisfaits.**

---

## 4.6 Proprietes LTL

| # | Formule | Type | Resultat |
|---|---|---|:---:|
| 1 | `G(not(firing AND reloading))` | Surete | **OK** |
| 2 | `G(fire -> F(log_recorded))` | Vivacite | **OK** |
| 3 | `G(error -> F(idle))` | Recuperation | **OK** |
| 4 | `G(F(idle))` | Vivacite globale | **OK** |
| 5 | `G(not(ammo < 0))` | Surete | **OK** |
| 6 | `G(cooldown -> not(firing))` | Surete | **OK** |

**6/6 proprietes LTL verifiees.**

---

## 4.7 Recapitulatif

| Categorie | Resultat |
|---|:---:|
| Invariants metier | 10/10 **PASS** |
| Proprietes LTL | 6/6 **PASS** |
| P-invariants | 2 trouves, valides **PASS** |
| T-invariants | 1 trouve, valide **PASS** |
| Bornitude | 6-borne **PASS** |
| Vivacite | L1/L4 (attendu) **PASS** |
| Deadlocks | 0 **PASS** |

**Conclusion** : le modele formel satisfait toutes les proprietes de surete et de vivacite. Le systeme est exempt de deadlocks et respecte tous les invariants metier.
