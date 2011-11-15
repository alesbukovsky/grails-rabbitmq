package org.grails.rabbitmq

import grails.spring.BeanBuilder
import org.springframework.amqp.core.Binding
import org.springframework.amqp.core.Binding.DestinationType
import org.springframework.amqp.core.Queue
import org.springframework.amqp.core.TopicExchange
import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.codehaus.groovy.grails.commons.GrailsClass

/**
 * Test cases for main plugin file setup.
 */
class RabbitGrailsPluginTests extends GroovyTestCase {

    def createPluginFileInstance() {
        String[] roots = ['./']
        ClassLoader loader = this.getClass().getClassLoader()
        def engine = new GroovyScriptEngine(roots, loader)
        def pluginClass = engine.loadScriptByName('RabbitmqGrailsPlugin.groovy')
        return pluginClass.newInstance()
    }

    void testQueueAndExchangeSetup() {
        // load the base plugin file
        def base = createPluginFileInstance()

        // mock up test configuration
        def application = new Object()

        application.metaClass.getServiceClasses = {
            return []
        }
        application.metaClass.config =  new ConfigSlurper().parse("""
            rabbitmq {
                connectionfactory {
                    username = 'guest'
                    password = 'guest'
                    hostname = 'localhost'
                }

                queues = {
                   exchange name: 'it_topic', durable: true, type: topic, autoDelete: false, {
                         it_q1 autoDelete: false, durable: true, binding: '#', arguments: ['x-ha-policy' : 'all']
                   }
                }
            }
        """)
        base.metaClass.application = application

        // run a spring builder to create context
        def bb = new BeanBuilder()
        bb.beans base.doWithSpring
        def ctx = bb.createApplicationContext()

        // test topic
        def itTopic = ctx.getBean("grails.rabbit.exchange.it_topic")
        assertEquals(itTopic.getClass(), TopicExchange.class)
        assertTrue(itTopic.durable)
        assertFalse(itTopic.autoDelete)
        assertEquals(itTopic.name, 'it_topic')

        // test queue
        def itQ1 = ctx.getBean("grails.rabbit.queue.it_q1")
        assertEquals(itQ1.getClass(), Queue.class)
        assertEquals(itQ1.name, "it_q1")
        assertEquals(itQ1.durable, true)
        assertEquals(itQ1.autoDelete, false)
        assertEquals(itQ1.arguments['x-ha-policy'], 'all')

        // test binding
        def ibBind = ctx.getBean("grails.rabbit.binding.it_topic.it_q1")
        assertEquals(ibBind.getClass(), Binding.class)
        assertEquals(ibBind.destination, 'it_q1')
        assertEquals(ibBind.exchange, 'it_topic')
        assertEquals(ibBind.routingKey, '#')
        assertEquals(ibBind.destinationType, DestinationType.QUEUE)
    }

    void testServiceDisabling() {
        def base = createPluginFileInstance()
        def blueService = new MockQueueService(propertyName: 'blueService')
        def pinkService = new MockQueueService(propertyName: 'pinkService')
        def redService = new MockSubscribeService(propertyName: 'redService')
        def tealService = new MockSubscribeService(propertyName: 'tealService')

        def application = new Object()
        application.metaClass.getServiceClasses = {
            return [blueService, redService, pinkService, tealService]
        }

        application.metaClass.config = new ConfigSlurper().parse("""
            rabbitmq {
                connectionfactory {
                    username = 'guest'
                    password = 'guest'
                    hostname = 'localhost'
                }
                services {
                    blueService {
                        concurrentConsumers = 5
                        disableListening = false
                    }
                    redService {
                        concurrentConsumers = 4
                        disableListening = true
                    }
                }
            }
        """)

        base.metaClass.application = application

        def bb = new BeanBuilder()
        bb.beans base.doWithSpring
        def ctx = bb.createApplicationContext()

        assert ctx.getBean('blueService_MessageListenerContainer').concurrentConsumers == 5
        assert ctx.getBean('pinkService_MessageListenerContainer').concurrentConsumers == 1

        shouldFail(NoSuchBeanDefinitionException) {
            ctx.getBean('redService_MessageListenerContainer')
        }
        assert ctx.getBean('tealService_MessageListenerContainer')
    }

}

static class MockSubscribeService {
    static rabbitSubscribe = 'blueExchange'
    static transactional = false
    def propertyName
    def clazz = MockSubscribeService.class
}

static class MockQueueService {
    static rabbitQueue = 'blueQueue'
    static transactional = false
    def propertyName
    def clazz = MockQueueService.class
}
