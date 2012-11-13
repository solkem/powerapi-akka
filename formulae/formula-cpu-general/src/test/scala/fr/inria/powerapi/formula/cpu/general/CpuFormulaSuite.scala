/**
 * Copyright (C) 2012 Inria, University Lille 1.
 *
 * This file is part of PowerAPI.
 *
 * PowerAPI is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * PowerAPI is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with PowerAPI. If not, see <http://www.gnu.org/licenses/>.
 *
 * Contact: powerapi-user-list@googlegroups.com.
 */
package fr.inria.powerapi.formula.cpu.general

import org.junit.Test
import org.scalatest.junit.JUnitSuite
import org.scalatest.junit.ShouldMatchersForJUnit

import akka.actor.actorRef2Scala
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorSystem
import akka.testkit.TestActorRef
import akka.util.duration.intToDurationInt
import fr.inria.powerapi.core.Process
import fr.inria.powerapi.core.Tick
import fr.inria.powerapi.core.TickSubscription
import fr.inria.powerapi.sensor.cpu.api.CpuSensorMessage
import fr.inria.powerapi.sensor.cpu.api.GlobalElapsedTime
import fr.inria.powerapi.sensor.cpu.api.ProcessElapsedTime
import fr.inria.powerapi.sensor.cpu.api.TimeInStates

case object Timestamps

case object NumberOfTicks

class TickReceiver extends Actor with ActorLogging {
  val timestamps = collection.mutable.Set[Long]()
  var numberOfTicks = 0

  def receive = {
    case tick: Tick => {
      numberOfTicks += 1
      timestamps += tick.timestamp
    }
    case Timestamps => sender ! timestamps.toSet
    case NumberOfTicks => sender ! numberOfTicks
  }
}

class CpuFormulaSuite extends JUnitSuite with ShouldMatchersForJUnit {
  implicit val system = ActorSystem("cpuformulasuite")
  val cpuformula = TestActorRef[CpuFormula]

  @Test
  def testTdp() {
    cpuformula.underlyingActor.tdp should equal(105)
  }

  @Test
  def testVoltages() {
    cpuformula.underlyingActor.frequencies should have size (3)
    cpuformula.underlyingActor.frequencies(1800002) should equal(1.31)
    cpuformula.underlyingActor.frequencies(2100002) should equal(1.41)
    cpuformula.underlyingActor.frequencies(2400003) should equal(1.5)
  }

  @Test
  def testConstant() {
    cpuformula.underlyingActor.constant should equal((0.7 * cpuformula.underlyingActor.tdp) / (cpuformula.underlyingActor.frequencies.max._1 * math.pow(cpuformula.underlyingActor.frequencies.max._2, 2)))
  }

  @Test
  def testPowers() {
    cpuformula.underlyingActor.powers should have size (3)
    cpuformula.underlyingActor.powers.foreach(power => power._2 should equal(
      cpuformula.underlyingActor.constant * power._1 * math.pow(cpuformula.underlyingActor.frequencies(power._1), 2)))
  }

  @Test
  def testRefreshCache() {
    val old = CpuSensorMessage(
      TimeInStates(Map[Int, Long]()),
      GlobalElapsedTime(100),
      ProcessElapsedTime(50),
      Tick(TickSubscription(Process(123), 500 milliseconds)))
    cpuformula.underlyingActor.refreshCache(old)
    cpuformula.underlyingActor.cache getOrElse(TickSubscription(Process(123), 500 milliseconds), null) should equal(old)

    val now = CpuSensorMessage(
      TimeInStates(Map[Int, Long]()),
      GlobalElapsedTime(300),
      ProcessElapsedTime(80),
      Tick(TickSubscription(Process(123), 500 milliseconds)))
    cpuformula.underlyingActor.refreshCache(now)
    cpuformula.underlyingActor.cache getOrElse(TickSubscription(Process(123), 500 milliseconds), null) should equal(now)

    cpuformula.underlyingActor.cache getOrElse(TickSubscription(Process(123), 123 milliseconds), null) should be(null)
  }

  @Test
  def testUsage() {
    val old = CpuSensorMessage(
      TimeInStates(Map[Int, Long]()),
      GlobalElapsedTime(100),
      ProcessElapsedTime(50),
      null)

    val now = CpuSensorMessage(
      TimeInStates(Map[Int, Long]()),
      GlobalElapsedTime(300),
      ProcessElapsedTime(80),
      null)

    cpuformula.underlyingActor.usage(old, now) should equal((80.0 - 50) / (300.0 - 100))
  }

  @Test
  def testPower() {
    val oldTimeInStates = TimeInStates(Map[Int, Long](1800002 -> 10, 2100002 -> 20, 2400003 -> 30))
    val old = CpuSensorMessage(
      oldTimeInStates,
      GlobalElapsedTime(100),
      ProcessElapsedTime(50),
      null)

    val nowTimInStates = TimeInStates(Map[Int, Long](1800002 -> 100, 2100002 -> 200, 2400003 -> 300))
    val now = CpuSensorMessage(
      nowTimInStates,
      GlobalElapsedTime(300),
      ProcessElapsedTime(80),
      null)

    val diffTimeInStates = nowTimInStates - oldTimeInStates
    val totalPowers = diffTimeInStates.times.foldLeft(0: Double) {
      (acc, time) => acc + (cpuformula.underlyingActor.powers(time._1) * time._2)
    }
    val totalTimes = diffTimeInStates.times.foldLeft(0: Long) {
      (acc, time) => acc + time._2
    }

    cpuformula.underlyingActor.power(old, now) should equal(totalPowers / totalTimes)
  }

  @Test
  def testCompute() {
    val tick = Tick(TickSubscription(Process(123), 10 seconds))
    val oldTimeInStates = TimeInStates(Map[Int, Long](1800002 -> 10, 2100002 -> 20, 2400003 -> 30))
    val old = CpuSensorMessage(
      oldTimeInStates,
      GlobalElapsedTime(100),
      ProcessElapsedTime(50),
      tick)
    cpuformula.underlyingActor.refreshCache(old)

    val nowTimInStates = TimeInStates(Map[Int, Long](1800002 -> 100, 2100002 -> 200, 2400003 -> 300))
    val now = CpuSensorMessage(
      nowTimInStates,
      GlobalElapsedTime(300),
      ProcessElapsedTime(80),
      tick)

    val diffTimeInStates = nowTimInStates - oldTimeInStates
    val totalPowers = diffTimeInStates.times.foldLeft(0: Double) {
      (acc, time) => acc + (cpuformula.underlyingActor.powers(time._1) * time._2)
    }
    val totalTimes = diffTimeInStates.times.foldLeft(0: Long) {
      (acc, time) => acc + time._2
    }

    val power = totalPowers / totalTimes
    val usage = (80.toDouble - 50) / (300 - 100)

    cpuformula.underlyingActor.compute(now).energy.power should equal(power * usage)
  }
}